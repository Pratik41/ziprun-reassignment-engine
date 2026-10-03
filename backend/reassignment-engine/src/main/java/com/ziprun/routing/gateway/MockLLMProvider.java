package com.ziprun.routing.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock LLM: answers from the agent table in the prompt, without any network call.
 *
 * Use it for local runs with no API key (llm.providers=mock) and to exercise
 * every fallback path on purpose via llm.mock.fail-mode:
 *   none        - valid JSON recommending the lowest-load agent
 *   timeout     - throws TIMEOUT
 *   rate-limit  - throws RATE_LIMITED
 *   garbage     - returns prose instead of JSON   (-> UNPARSEABLE)
 *   hallucinate - recommends AGT-999, not in roster (-> HALLUCINATED_AGENT)
 */
@Component
public class MockLLMProvider implements LLMProvider {
    private static final Logger log = LoggerFactory.getLogger(MockLLMProvider.class);

    // Matches roster rows written by PromptBuilder: | id | name | active | pending | effective |
    private static final Pattern ROSTER_ROW = Pattern.compile(
        "^\\|\\s*(\\S+)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s*\\|", Pattern.MULTILINE);

    private record Row(String id, String name, int effectiveLoad) {
    }

    private static final int STREAM_CHUNK = 6;

    private final ObjectMapper objectMapper;
    private final String failMode;
    private final long streamDelayMs;

    public MockLLMProvider(ObjectMapper objectMapper,
                           @Value("${llm.mock.fail-mode:none}") String failMode,
                           @Value("${llm.mock.stream-delay-ms:40}") long streamDelayMs) {
        this.objectMapper = objectMapper;
        this.failMode = failMode;
        this.streamDelayMs = streamDelayMs;
    }

    @Override
    public String getName() {
        return "mock";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    /**
     * Emits the reply in small paced chunks so the SSE path can be demoed without a key.
     */
    @Override
    public String streamLLM(String prompt, Consumer<String> onChunk) {
        String text = callLLM(prompt);
        for (int i = 0; i < text.length(); i += STREAM_CHUNK) {
            onChunk.accept(text.substring(i, Math.min(text.length(), i + STREAM_CHUNK)));
            try {
                Thread.sleep(streamDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMException(LLMException.Kind.TIMEOUT, "mock stream interrupted", e);
            }
        }
        return text;
    }

    @Override
    public String callLLM(String prompt) {
        switch (failMode) {
            case "timeout" -> throw new LLMException(LLMException.Kind.TIMEOUT, "mock simulated timeout");
            case "rate-limit" -> throw new LLMException(LLMException.Kind.RATE_LIMITED, "mock simulated 429");
            case "garbage" -> {
                return "Sure! I'd go with whoever is free, they seem nice.";
            }
            default -> { }
        }

        List<Row> rows = new ArrayList<>();
        Matcher m = ROSTER_ROW.matcher(prompt);
        while (m.find()) {
            rows.add(new Row(m.group(1), m.group(2), Integer.parseInt(m.group(5))));
        }
        if (rows.isEmpty()) {
            throw new LLMException(LLMException.Kind.EMPTY_RESPONSE, "mock found no agents in prompt");
        }

        Row best = rows.stream()
            .min(Comparator.comparingInt(Row::effectiveLoad).thenComparing(Row::id))
            .orElseThrow();
        boolean recovery = prompt.contains("RECOVERY");
        String agentId = "hallucinate".equals(failMode) ? "AGT-999" : best.id();
        String reasoning = String.format(
            "[mock] %s has the lowest effective load (%d) of %d available agent(s)%s.",
            best.name(), best.effectiveLoad(), rows.size(),
            recovery ? ", so they can absorb this stranded order fastest" : "");

        try {
            String json = objectMapper.writeValueAsString(Map.of("recommendations", List.of(
                Map.of("agent_id", agentId, "confidence", 0.8, "reasoning", reasoning))));
            log.debug("Mock LLM response: {}", json);
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
