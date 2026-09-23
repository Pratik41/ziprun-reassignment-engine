package com.ziprun.service.ai;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds AI prompts for routing decisions.
 *
 * Key insight from ADR-7: Initial assignment and re-plan after agent offline
 * are fundamentally different scenarios. This builder creates distinctly
 * different prompts tailored to each situation.
 *
 * Initial prompt: "Which agent should take this order?"
 * Re-plan prompt: "This agent failed. These orders are stranded. Recover them."
 */
public class PromptBuilder {

    public static String buildInitialAssignmentPrompt(Order order, List<Agent> availableAgents) {
        return String.format(
            """
            You are an AI assignment engine for a delivery platform.

            TASK: Recommend the best available agent for THIS SPECIFIC order.

            ORDER DETAILS:
            - ID: %s
            - Description/Type: %s
            - Status: NEW (needs assignment)

            AVAILABLE AGENTS (with current workload):
            %s

            INSTRUCTIONS:
            1. Analyze each agent's current workload (active orders count)
            2. For order '%s' with description '%s', pick the BEST SUITED agent
            3. Explain WHY this agent is good for THIS ORDER specifically (not generic)
            4. Return your recommendation in JSON format

            REASONING MUST BE SPECIFIC TO THIS ORDER ('%s'):
            - Which agent is best and why?
            - How does this agent's current load affect the decision?
            - Why is this agent better than others for this particular order type ('%s')?

            RESPONSE FORMAT (must be valid JSON):
            {
              "agent_id": "AGT-XXXX",
              "confidence": 0.85,
              "reasoning": "For %s: [specific explanation for this order]"
            }

            CONSTRAINTS:
            - agent_id must match one of the agent IDs listed above (no hallucination)
            - confidence must be a number between 0.0 and 1.0
            - reasoning MUST include the order ID (%s) and be SPECIFIC to this order, not generic
            - reasoning must be brief (1-2 sentences) and suitable for ops to read

            Return ONLY the JSON response, no other text.
            """,
            order.getId(),
            order.getDescription(),
            formatAgentRoster(availableAgents),
            order.getId(),
            order.getDescription(),
            order.getId(),
            order.getDescription(),
            order.getId(),
            order.getId()
        );
    }

    public static String buildReplanPrompt(
            Order order,
            List<Agent> availableAgents,
            String failedAgentId,
            String failedAgentName,
            int strandedOrderCount
    ) {
        return String.format(
            """
            ALERT: Agent offline. Recovery routing in progress.

            SITUATION:
            - Agent '%s' (ID: %s) has gone OFFLINE
            - %d orders are now stranded and need reassignment
            - This is a recovery situation, not a normal assignment

            AFFECTED ORDER (one of the stranded):
            - ID: %s
            - Description/Type: %s
            - Urgency: HIGH (customer expecting delivery from offline agent)
            - Current State: REASSIGNMENT_PENDING

            AVAILABLE AGENTS (for reassignment - rank by current workload):
            %s

            INSTRUCTIONS:
            You are in RECOVERY MODE. This is NOT a normal assignment:
            1. The original agent '%s' failed. Previous assignments to them are void.
            2. Order '%s' with description '%s' must be moved to a healthy agent immediately.
            3. Analyze each available agent's current load.
            4. Choose the agent BEST SUITED for THIS SPECIFIC ORDER based on their current capacity.
            5. Be pragmatic: good-enough is better than perfect when recovering.

            REASONING MUST BE SPECIFIC TO THIS ORDER ('%s'):
            - Which agent did you pick and why?
            - How does this agent's current load factor into your decision?
            - Why is this agent better than the alternatives for THIS particular order?

            RESPONSE FORMAT (must be valid JSON):
            {
              "agent_id": "AGT-XXXX",
              "confidence": 0.75,
              "reasoning": "Recovery for %s: [specific explanation of why this agent for this order]"
            }

            CONSTRAINTS:
            - agent_id must match one of the available agents listed above
            - confidence: in recovery, confidence may be lower (0.5+) than normal (0.8+)
            - reasoning MUST include the order ID (%s) and be SPECIFIC to this order, not generic

            Return ONLY the JSON response, no other text.
            """,
            failedAgentName,
            failedAgentId,
            strandedOrderCount,
            order.getId(),
            order.getDescription(),
            formatAgentRoster(availableAgents),
            failedAgentName,
            order.getId(),
            order.getDescription(),
            order.getId(),
            order.getId(),
            order.getId()
        );
    }

    private static String formatAgentRoster(List<Agent> agents) {
        return agents.stream()
            .map(agent -> String.format(
                "- %s (ID: %s, Status: %s, Active Orders: %d)",
                agent.getName(),
                agent.getId(),
                agent.getStatus(),
                agent.getActiveOrderCount()
            ))
            .collect(Collectors.joining("\n"));
    }
}
