package com.ziprun.routing.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock LLM Gateway: Returns realistic recommendations without calling any API.
 *
 * Perfect for:
 * - Local development (no API key needed)
 * - Testing the full system
 * - Demos without internet dependency
 *
 * Enable by setting: llm.provider=mock
 */
@Component("mock-llm")
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = false)
public class MockLLMGateway implements LLMProvider {
    private static final Logger log = LoggerFactory.getLogger(MockLLMGateway.class);

    private static final String[] AGENTS = {
        "AGT-001", "AGT-002", "AGT-003", "AGT-004", "AGT-005"
    };

    private static final String[] REASONS = {
        "Agent is currently available and closest to the pickup zone.",
        "Agent has the lowest workload (1 active order) and can handle this delivery quickly.",
        "Recommended based on delivery history in this area and current availability.",
        "Agent is AVAILABLE with capacity to take on one more order immediately.",
        "Recovery strategy: Reassigning from offline agent to this capable agent."
    };

    @Override
    public String callLLM(String prompt) {
        log.debug("Mock LLM called (no external API needed)");

        // Simulate LLM response: pick a random agent and confidence
        String agentId = AGENTS[(int) (Math.random() * AGENTS.length)];
        double confidence = 0.75 + (Math.random() * 0.2); // 0.75 - 0.95
        String reasoning = REASONS[(int) (Math.random() * REASONS.length)];

        // Return JSON that matches what a real LLM would return
        String response = String.format(
            "{\"agent_id\":\"%s\",\"confidence\":%.2f,\"reasoning\":\"%s\"}",
            agentId,
            confidence,
            reasoning
        );

        log.debug("Mock LLM response: {}", response);
        return response;
    }

    @Override
    public String getName() {
        return "mock-llm";
    }
}
