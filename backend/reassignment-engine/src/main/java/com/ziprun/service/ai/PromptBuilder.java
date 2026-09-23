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

            TASK: Assign THIS SPECIFIC order to the best available agent.

            ORDER DETAILS:
            - Order ID: %s
            - Description: %s
            - Priority: NORMAL

            AVAILABLE AGENTS (with current workload):
            %s

            *** CRITICAL: UNIQUE REASONING FOR EACH ORDER ***
            For order %s (%s) specifically:
            1. Which agent can handle this best?
            2. What is their current load?
            3. Why them over others?
            4. Any trade-offs?

            REASONING FORMAT (MUST be UNIQUE per order - not template text):
            Format: "For %s (%s): [Agent Name] has [load details]. Better than [other agent] because [reason]. Trade-off: [explanation]."

            Example of GOOD unique reasoning:
            "For ORD-456 (Electronics): Vikram has 2 active orders (lowest). Better than Raj (4) or Amit (6) because lowest load. Trade-off: none available."

            Example of BAD generic reasoning (DO NOT DO THIS):
            "Assigned because agent has low load and other agents available."

            RESPONSE FORMAT (must be valid JSON):
            {
              "agent_id": "AGT-XXXX",
              "confidence": 0.85,
              "reasoning": "For %s (%s): [Agent Name] has X active orders. Chosen over [other agents] because [specific reason for THIS order]."
            }

            CRITICAL RULES:
            - reasoning MUST START with "For %s"
            - reasoning MUST include ORDER ID (%s)
            - reasoning MUST include ORDER DESCRIPTION (%s)
            - reasoning MUST compare with at least 1 other agent by name
            - reasoning MUST mention load/capacity numbers
            - reasoning MUST be DIFFERENT for each order (not copy-paste)
            - DO NOT use generic/template text

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
            order.getDescription(),
            order.getId(),
            order.getDescription()
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
            - Offline Agent: '%s' (ID: %s)
            - Total Stranded Orders: %d
            - THIS IS ORDER: %s (Description: %s)

            AVAILABLE AGENTS FOR REASSIGNMENT:
            %s

            *** CRITICAL: UNIQUE REASONING FOR EACH ORDER ***
            For order %s specifically (not generic):
            1. Which agent is best?
            2. What is their current load?
            3. Why them over others?
            4. Trade-offs considered?

            REASONING FORMAT (MUST be UNIQUE per order - not template text):
            Format: "For %s (%s): [Agent Name] has [load details]. This is better than [name2] because [reason]. Trade-off: [explanation]."

            Example of GOOD unique reasoning:
            "For ORD-123 (Electronics): Vikram has 2 active orders (lowest). Better than Raj (4) who is offline. Trade-off: slightly less experienced but available."

            Example of BAD generic reasoning (DO NOT DO THIS):
            "Assigned because agent has low load and other agents available."

            RESPONSE FORMAT (must be valid JSON):
            {
              "agent_id": "AGT-XXXX",
              "confidence": 0.75,
              "reasoning": "For %s (%s): [Agent Name] has X active orders. Chosen over [other agents] because [specific reason for THIS order]."
            }

            CRITICAL RULES:
            - reasoning MUST START with "For %s"
            - reasoning MUST include the ORDER ID (%s)
            - reasoning MUST include the ORDER DESCRIPTION (%s)
            - reasoning MUST compare with at least 1 other agent by name
            - reasoning MUST mention load/capacity numbers
            - reasoning MUST be DIFFERENT for each order (not copy-paste)
            - DO NOT use generic/template text

            Return ONLY the JSON response, no other text.
            """,
            failedAgentName,
            failedAgentId,
            strandedOrderCount,
            order.getId(),
            order.getDescription(),
            formatAgentRoster(availableAgents),
            order.getId(),
            order.getId(),
            order.getId(),
            order.getDescription(),
            order.getId(),
            order.getId(),
            order.getDescription()
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
