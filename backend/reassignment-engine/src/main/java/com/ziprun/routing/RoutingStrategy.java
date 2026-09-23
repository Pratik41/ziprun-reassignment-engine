package com.ziprun.routing;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import java.util.List;

public interface RoutingStrategy {
    /**
     * Recommends an agent for the given order from available agents.
     *
     * @param order the order needing reassignment
     * @param availableAgents agents currently available to take orders
     * @return a RoutingResult containing recommended agent, confidence, and reasoning
     */
    RoutingResult recommend(Order order, List<Agent> availableAgents);

    /**
     * Returns the strategy name for identification and configuration.
     */
    String getName();
}
