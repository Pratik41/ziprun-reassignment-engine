package com.ziprun.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.gateway.LLMGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * AI Advisory Service: orchestrates LLM calls for routing recommendations.
 *
 * Responsibilities:
 * - Build context-appropriate prompts (initial vs re-plan)
 * - Call LLM via gateway
 * - Parse and validate LLM response
 * - Handle failures gracefully
 * - Log at appropriate levels for debugging
 *
 * This keeps AI logic separate from routing strategy logic:
 * - AIAdvisorService: "What does the AI say?"
 * - AIRoutingStrategy: "How do we use that advice?"
 */
@Service
public class AIAdvisorService {
    private static final Logger log = LoggerFactory.getLogger(AIAdvisorService.class);

    private final LLMGateway llmGateway;
    private final ObjectMapper objectMapper;

    public AIAdvisorService(LLMGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * Suggest an agent for an order using the LLM.
     * Used for initial assignments via POST /orders/{id}/suggest.
     *
     * @return AIRecommendation with agent choice and reasoning, or null on failure
     */
    public AIRecommendation suggestAgent(Order order, List<Agent> availableAgents) {
        try {
            String prompt = PromptBuilder.buildInitialAssignmentPrompt(order, availableAgents);
            return callLLMAndParse(prompt, order.getId(), "initial");

        } catch (Exception e) {
            log.error("Failed to get AI suggestion for order {}", order.getId(), e);
            return null;
        }
    }

    /**
     * Suggest reassignment for an order when original agent went offline.
     * Used by agentic re-planning loop in T-4.
     *
     * @param strandedOrderCount total number of orders affected by the agent failure
     * @return AIRecommendation for recovery, or null on failure
     */
    public AIRecommendation suggestReassignment(
            Order order,
            List<Agent> availableAgents,
            String failedAgentId,
            String failedAgentName,
            int strandedOrderCount
    ) {
        try {
            String prompt = PromptBuilder.buildReplanPrompt(
                order,
                availableAgents,
                failedAgentId,
                failedAgentName,
                strandedOrderCount
            );
            return callLLMAndParse(prompt, order.getId(), "replan");

        } catch (Exception e) {
            log.error("Failed to get AI replan suggestion for order {}", order.getId(), e);
            return null;
        }
    }

    /**
     * Internal: Call LLM, parse response, validate structure.
     */
    private AIRecommendation callLLMAndParse(String prompt, String orderId, String context) {
        try {
            String rawResponse = llmGateway.callLLM(prompt);

            if (rawResponse == null || rawResponse.trim().isEmpty()) {
                log.warn("LLM returned empty response for order {} ({})", orderId, context);
                return null;
            }

            LLMResponse parsed = parseResponse(rawResponse);

            if (parsed == null) {
                log.warn("Failed to parse LLM response for order {} ({}). Raw: {}", orderId, context, rawResponse);
                return null;
            }

            log.debug(
                "LLM parsed successfully for order {} ({}): {}",
                orderId,
                context,
                parsed
            );

            return AIRecommendation.of(
                parsed.getAgentId(),
                parsed.getConfidence(),
                parsed.getReasoning()
            );

        } catch (Exception e) {
            log.error("LLM call failed for order {} ({})", orderId, context, e);
            return null;
        }
    }

    /**
     * Parse LLM response as JSON into LLMResponse structure.
     *
     * @return parsed response, or null if parsing fails
     */
    private LLMResponse parseResponse(String rawResponse) {
        try {
            String trimmed = rawResponse.trim();

            // Extract JSON if response contains markdown code blocks
            if (trimmed.startsWith("```json")) {
                trimmed = trimmed.substring(7);
            } else if (trimmed.startsWith("```")) {
                trimmed = trimmed.substring(3);
            }

            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }

            trimmed = trimmed.trim();

            LLMResponse response = objectMapper.readValue(trimmed, LLMResponse.class);

            // Validate required fields
            if (response.getAgentId() == null || response.getAgentId().isBlank()) {
                log.warn("LLM response missing agent_id");
                return null;
            }

            if (response.getConfidence() == null) {
                log.warn("LLM response missing confidence");
                return null;
            }

            if (response.getReasoning() == null || response.getReasoning().isBlank()) {
                log.warn("LLM response missing reasoning");
                return null;
            }

            return response;

        } catch (Exception e) {
            log.debug("JSON parsing failed: {}", e.getMessage());
            return null;
        }
    }
}
