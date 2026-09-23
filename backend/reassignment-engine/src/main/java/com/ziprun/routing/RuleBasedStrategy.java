package com.ziprun.routing;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;

@Component("rule-based")
public class RuleBasedStrategy implements RoutingStrategy {

    @Override
    public RoutingResult recommend(Order order, List<Agent> availableAgents) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            return RoutingResult.of(null, 0.0, "No available agents to handle this order");
        }

        Agent recommended = availableAgents.stream()
            .min(Comparator.comparingInt(Agent::getActiveOrderCount))
            .orElse(availableAgents.get(0));

        String reasoning = String.format(
            "Agent %s selected based on current workload. Currently handling %d orders.",
            recommended.getName(),
            recommended.getActiveOrderCount()
        );

        return RoutingResult.of(recommended.getId(), 0.95, reasoning);
    }

    @Override
    public String getName() {
        return "rule-based";
    }
}
