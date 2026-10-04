package com.ziprun.routing;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import java.util.List;

/**
 * The routing contract. Every strategy (rule-based, AI, a future zone
 * affinity) implements this and is registered as a Spring bean whose bean
 * name is its strategy name, e.g. {@code @Component("zone-affinity")}.
 * RoutingService discovers it automatically; nothing else changes.
 */
public interface RoutingStrategy {
    /**
     * Ranks candidate agents for the given order.
     *
     * @param order the order needing an agent
     * @param availableAgents candidates (AVAILABLE, excluding the order's current agent)
     * @param context why we're routing (initial vs recovery) plus pending-load snapshot
     * @return recommendations ordered best-first; empty if there is no viable candidate
     */
    List<RoutingResult> recommend(Order order, List<Agent> availableAgents, RoutingContext context);

    /**
     * Returns the strategy name for identification and configuration.
     */
    String getName();
}
