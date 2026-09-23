package com.ziprun.routing.strategy;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingStrategy;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;

/**
 * Rule-Based Routing Strategy: deterministic, no external dependencies.
 *
 * Decision Rule: Select the agent currently carrying the fewest active orders.
 *
 * Characteristics:
 * - No external dependencies (no LLM, no databases beyond what's passed in)
 * - Deterministic and fast
 * - Suitable as fallback when AI unavailable
 * - High confidence (0.95) because decision is based on concrete data
 *
 * Design Note: This strategy is stateless and thread-safe.
 * All state comes from parameters; no internal state is modified.
 */
@Component("rule-based")
public class RuleBasedStrategy implements RoutingStrategy {

    @Override
    public RoutingResult recommend(Order order, List<Agent> availableAgents) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            return RoutingResult.of(null, 0.0, "No available agents to handle this order");
        }

        // Select agent with minimum active order count
        Agent selectedAgent = availableAgents.stream()
            .min(Comparator.comparingInt(Agent::getActiveOrderCount))
            .orElse(availableAgents.get(0));

        String reasoning = buildReasoning(selectedAgent, availableAgents.size());

        return RoutingResult.of(selectedAgent.getId(), 0.95, reasoning);
    }

    @Override
    public String getName() {
        return "rule-based";
    }

    /**
     * Build plain-English reasoning for the recommendation.
     * Ops will read this verbatim in the UI.
     */
    private String buildReasoning(Agent selected, int totalAvailable) {
        return String.format(
            "Assigned to %s (capacity: %d orders). %s agent available. " +
            "Decision: least loaded agent for workload distribution.",
            selected.getName(),
            selected.getActiveOrderCount(),
            totalAvailable > 1 ? (totalAvailable - 1) + " other" : "No other"
        );
    }
}
