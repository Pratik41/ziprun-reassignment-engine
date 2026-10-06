package com.ziprun;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * When the streamed AI output is unusable, the stream says so (restart) and
 * still ends with a rule-based suggestion.
 */
@SpringBootTest(properties = {
    "security.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:stream-fallback-${random.uuid}",
    "routing.strategy=ai",
    "llm.providers=mock",
    "llm.mock.fail-mode=hallucinate",
    "llm.mock.stream-delay-ms=1"
})
@AutoConfigureMockMvc
class StreamingFallbackIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void streamAnnouncesFallbackAndStillDelivers() throws Exception {
        MvcResult result = mvc.perform(post("/orders/ORD-004/suggest/stream"))
            .andExpect(request().asyncStarted()).andReturn();
        String body = SseTestSupport.awaitFinalEvent(result.getResponse());

        assertThat(body).contains("event:restart", "HALLUCINATED_AGENT", "event:suggestion",
            "rule-based (AI fallback: HALLUCINATED_AGENT)");
        assertThat(body.indexOf("event:restart")).isLessThan(body.indexOf("event:suggestion"));
    }
}
