package com.ziprun.routing;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.domain.TriggerReason;

import java.util.List;
import java.util.Map;

/**
 * Why routing is being asked, and what the world looks like right now.
 *
 * This is what lets one routing contract serve both callers:
 *  - HTTP  POST /orders/{id}/suggest  -> RoutingContext.initial()
 *  - async ReplanEventHandler         -> RoutingContext.agentOffline(...)
 * Strategies that care (AI picks a different prompt) read it; strategies that
 * don't (rule-based) mostly ignore it. A future SLA monitor would add a new
 * TriggerReason and factory method here, not a new routing method.
 *
 * pendingLoad: PENDING suggestions already pointing at each agent. Filled in by
 * RoutingService so a batch of stranded orders doesn't all go to one agent.
 *
 * listener: receives reasoning as it is generated (SSE endpoint); NONE otherwise.
 */
public record RoutingContext(
        TriggerReason trigger,
        String failedAgentId,
        String failedAgentName,
        List<Order> strandedOrders,
        Map<String, Integer> pendingLoad,
        ReasoningListener listener
) {

    public static RoutingContext initial() {
        return new RoutingContext(TriggerReason.INITIAL, null, null, List.of(), Map.of(), ReasoningListener.NONE);
    }

    public static RoutingContext agentOffline(String failedAgentId, String failedAgentName, List<Order> strandedOrders) {
        return new RoutingContext(TriggerReason.AGENT_OFFLINE, failedAgentId, failedAgentName,
            List.copyOf(strandedOrders), Map.of(), ReasoningListener.NONE);
    }

    public RoutingContext withPendingLoad(Map<String, Integer> pendingLoad) {
        return new RoutingContext(trigger, failedAgentId, failedAgentName, strandedOrders, Map.copyOf(pendingLoad), listener);
    }

    public RoutingContext withListener(ReasoningListener listener) {
        return new RoutingContext(trigger, failedAgentId, failedAgentName, strandedOrders, pendingLoad, listener);
    }

    public boolean isStreaming() {
        return listener != ReasoningListener.NONE;
    }

    public boolean isRecovery() {
        return trigger == TriggerReason.AGENT_OFFLINE;
    }

    public int strandedOrderCount() {
        return strandedOrders.size();
    }

    public int pendingFor(Agent agent) {
        return pendingLoad.getOrDefault(agent.getId(), 0);
    }

    /** Active orders plus suggestions already queued for this agent. */
    public int effectiveLoad(Agent agent) {
        int active = agent.getActiveOrderCount() == null ? 0 : agent.getActiveOrderCount();
        return active + pendingFor(agent);
    }
}
