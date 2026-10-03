package com.ziprun;

import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /orders/{id}/suggest/stream end to end with the mock LLM streaming in small chunks.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:stream-${random.uuid}",
    "routing.strategy=ai",
    "llm.providers=mock",
    "llm.mock.stream-delay-ms=5"
})
@AutoConfigureMockMvc
class StreamingSuggestionIntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("event:token\ndata:\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"}");

    @Autowired MockMvc mvc;
    @Autowired ReassignmentSuggestionRepository suggestions;

    @Test
    void streamsReasoningTokensThenThePersistedSuggestion() throws Exception {
        String body = stream("/orders/ORD-003/suggest/stream");

        assertThat(body).startsWith("event:start");
        assertThat(body.indexOf("event:token")).isLessThan(body.indexOf("event:suggestion"));
        assertThat(body).doesNotContain("event:error").doesNotContain("event:restart");

        // Tokens are prose from the reasoning field, not raw JSON, and arrive in several pieces
        StringBuilder reasoning = new StringBuilder();
        Matcher m = TOKEN.matcher(body);
        int tokens = 0;
        while (m.find()) {
            reasoning.append(m.group(1));
            tokens++;
        }
        assertThat(tokens).isGreaterThan(3);
        assertThat(reasoning.toString()).startsWith("[mock]").doesNotContain("agent_id");

        assertThat(suggestions.findByOrderId("ORD-003")).singleElement().satisfies(s -> {
            assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PENDING);
            assertThat(s.getTriggerReason()).isEqualTo(TriggerReason.INITIAL);
            assertThat(s.getReasoning()).isEqualTo(reasoning.toString());
        });
    }

    @Test
    void unknownOrderFailsBeforeTheStreamOpens() throws Exception {
        mvc.perform(post("/orders/NOPE/suggest/stream")).andExpect(status().isNotFound());
    }

    private String stream(String url) throws Exception {
        MvcResult result = mvc.perform(post(url)).andExpect(request().asyncStarted()).andReturn();
        MockHttpServletResponse response = result.getResponse();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!response.getContentAsString().contains("event:suggestion")
               && !response.getContentAsString().contains("event:error")
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        return response.getContentAsString();
    }
}
