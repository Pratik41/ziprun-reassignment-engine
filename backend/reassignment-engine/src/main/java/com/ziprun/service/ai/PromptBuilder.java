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

            TASK: Recommend the best available agent for the following order.

            ORDER:
            - ID: %s
            - Description: %s

            AVAILABLE AGENTS (with current workload):
            %s

            INSTRUCTIONS:
            1. Consider agent current workload (orders they are already handling)
            2. Pick the agent best suited to take this order
            3. Return your recommendation in JSON format (exactly as shown below)

            RESPONSE FORMAT (must be valid JSON):
            {
              "agent_id": "AGT-XXXX",
              "confidence": 0.85,
              "reasoning": "Plain English explanation of why this agent was chosen"
            }

            CONSTRAINTS:
            - agent_id must match one of the agent IDs listed above (no hallucination)
            - confidence must be a number between 0.0 and 1.0
            - reasoning must be brief (1-2 sentences) and suitable for ops to read

            Return ONLY the JSON response, no other text.
            """,
            order.getId(),
            order.getDescription(),
            formatAgentRoster(availableAgents)
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
            - Description: %s

            AVAILABLE AGENTS (for reassignment):
            %s

            INSTRUCTIONS:
            You are in RECOVERY MODE. This is NOT a normal assignment:
            1. The original agent failed. Previous assignments to them are void.
            2. These orders must be moved to healthy agents immediately.
            3. Consider which healthy agent can absorb this load.
            4. Be pragmatic: good-enough is better than perfect when recovering.

            RESPONSE FORMAT (must be valid JSON):
            {
              "agent_id": "AGT-XXXX",
              "confidence": 0.75,
              "reasoning": "Recovery: [brief explanation of reassignment strategy]"
            }

            CONSTRAINTS:
            - agent_id must match one of the available agents listed above
            - confidence: in recovery, confidence may be lower (0.5+) than normal (0.8+)
            - reasoning must start with "Recovery:" to indicate this is a re-plan

            Return ONLY the JSON response, no other text.
            """,
            failedAgentName,
            failedAgentId,
            strandedOrderCount,
            order.getId(),
            order.getDescription(),
            formatAgentRoster(availableAgents)
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
