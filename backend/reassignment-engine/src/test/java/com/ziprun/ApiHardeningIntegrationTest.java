package com.ziprun;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hardening: optimistic locking, conflict-free withdrawals, timestamps with an offset,
 * response records (no internal fields) and pagination.
 */
@SpringBootTest(properties = {
    "security.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:hardening-${random.uuid}",
    "routing.strategy=rule-based",
    "llm.providers=mock"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApiHardeningIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AgentRepository agents;
    @Autowired ReassignmentSuggestionRepository suggestions;
    @Autowired TransactionTemplate tx;

    @Test
    void aStaleCopyCannotOverwriteANewerChange() {
        // two "requests" read the same agent...
        Agent first = tx.execute(s -> agents.findById("AGT-002").orElseThrow());
        Agent second = tx.execute(s -> agents.findById("AGT-002").orElseThrow());

        // ...the first saves a status change (e.g. ops sets Busy)
        first.setStatus(AgentStatus.BUSY);
        tx.executeWithoutResult(s -> agents.save(first));

        // ...the second, still holding the old version (e.g. a heartbeat), is refused instead of
        // silently writing AVAILABLE back over BUSY
        second.setLastHeartbeatAt(LocalDateTime.now());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> agents.save(second)))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(agents.findById("AGT-002").orElseThrow().getStatus()).isEqualTo(AgentStatus.BUSY);
    }

    @Test
    void withdrawingTheSameSuggestionsTwiceAtOnceIsNotAConflict() throws Exception {
        mvc.perform(patch("/agents/AGT-001/status").contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"OFFLINE\"}")).andExpect(status().isOk());
        long deadline = System.currentTimeMillis() + 10_000;
        while (suggestions.findByStatus(SuggestionStatus.PENDING).size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        // both "Keep" and a re-balance expire ORD-001's suggestions: the second simply finds none left
        LocalDateTime now = LocalDateTime.now();
        Integer firstWithdrawal = tx.execute(s -> suggestions.expirePendingForOrder("ORD-001", now));
        Integer secondWithdrawal = tx.execute(s -> suggestions.expirePendingForOrder("ORD-001", now));
        assertThat(firstWithdrawal).isEqualTo(1);
        assertThat(secondWithdrawal).isZero();
        assertThat(suggestions.findByOrderId("ORD-001"))
            .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SuggestionStatus.EXPIRED));
    }

    @Test
    void listsArePagedWithTheTotalInAHeader() throws Exception {
        mvc.perform(get("/orders").param("size", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(header().string("X-Total-Count", "8"));
        mvc.perform(get("/orders").param("size", "3").param("page", "2"))
            .andExpect(jsonPath("$.length()").value(2));
        // without paging parameters: everything up to the default page size, as before
        mvc.perform(get("/orders")).andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void responsesDoNotExposeInternalFields() throws Exception {
        String agent = mvc.perform(get("/agents/AGT-001")).andReturn().getResponse().getContentAsString();
        String order = mvc.perform(get("/orders/ORD-001")).andReturn().getResponse().getContentAsString();
        assertThat(agent).contains("\"activeOrderCount\"").doesNotContain("\"version\"");
        assertThat(order).contains("\"slaDeadline\"").doesNotContain("\"version\"", "\"slaAlertedAt\"");
    }

    @Test
    void timestampsCarryTheirUtcOffset() throws Exception {
        mvc.perform(get("/orders/ORD-001"))
            .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.matchesRegex(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})")));
    }
}
