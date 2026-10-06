package com.ziprun;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With the LLM returning a hallucinated agent, the async re-plan must still
 * queue (rule-based) suggestions instead of silently dropping them.
 */
@SpringBootTest(properties = {
    "security.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:fallback-${random.uuid}",
    "routing.strategy=ai",
    "llm.providers=mock",
    "llm.mock.fail-mode=hallucinate"
})
@AutoConfigureMockMvc
class AIFallbackIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReassignmentSuggestionRepository suggestions;

    @Test
    void asyncReplanFallsBackInsteadOfDropping() throws Exception {
        mvc.perform(patch("/agents/AGT-005/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFLINE\"}"))
            .andExpect(status().isOk());

        long deadline = System.currentTimeMillis() + 10_000;
        List<ReassignmentSuggestion> pending = suggestions.findByStatus(SuggestionStatus.PENDING);
        while (pending.size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            pending = suggestions.findByStatus(SuggestionStatus.PENDING);
        }

        assertThat(pending).hasSize(3).allSatisfy(s -> {
            assertThat(s.getSource()).isEqualTo("rule-based (AI fallback: HALLUCINATED_AGENT)");
            assertThat(s.getRecommendedAgentId()).isIn("AGT-002", "AGT-004");
        });
    }
}
