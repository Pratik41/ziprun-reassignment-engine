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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Optimistic locking, conflict-free withdrawals, and timestamps with an offset.
 */
@SpringBootTest(properties = {
    "security.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:concurrency-${random.uuid}",
    "routing.strategy=rule-based",
    "llm.providers=mock"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConcurrencyIntegrationTest {

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
    void timestampsCarryTheirUtcOffset() throws Exception {
        mvc.perform(get("/orders/ORD-001"))
            .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.matchesRegex(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})")));
    }
}
