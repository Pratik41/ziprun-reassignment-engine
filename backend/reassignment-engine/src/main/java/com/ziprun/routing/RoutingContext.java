package com.ziprun.routing;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.domain.TriggerReason;

import java.util.List;
import java.util.Map;

/**
 * Why routing is being asked, and what the world looks like right now.
 *
 * This is what lets one routing contract serve every caller:
 *  - HTTP  POST /orders/{id}/suggest  -> RoutingContext.initial()
 *  - async ReplanEventHandler         -> RoutingContext.agentOffline(...)
 *  - SlaMonitor                       -> RoutingContext.slaRisk()
 * Strategies that care (AI picks a different prompt) read it; strategies that
 * don't (rule-based) mostly ignore it.
 *
 * pendingLoad: PENDING suggestions already pointing at each agent. Filled in by
 * RoutingService so a batch of stranded orders doesn't all go to one agent.
 *
 * defaultCapacity: max effective load for agents without their own maxCapacity
 * (0 = unlimited). Filled in by RoutingService from agents.default-max-capacity.
 *
 * listener: receives reasoning as it is generated (SSE endpoint); NONE otherwise.
 */
public record RoutingContext(
        TriggerReason trigger,
        String failedAgentId,
        String failedAgentName,
        List<Order> strandedOrders,
        Map<String, Integer> pendingLoad,
        int defaultCapacity,
        ReasoningListener listener
) {

    public static RoutingContext initial() {
        return new RoutingContext(TriggerReason.INITIAL, null, null, List.of(), Map.of(), 0, ReasoningListener.NONE);
    }

    public static RoutingContext agentOffline(String failedAgentId, String failedAgentName, List<Order> strandedOrders) {
        return new RoutingContext(TriggerReason.AGENT_OFFLINE, failedAgentId, failedAgentName,
            List.copyOf(strandedOrders), Map.of(), 0, ReasoningListener.NONE);
    }

    /** The order is likely to miss its delivery deadline with its current agent. */
    public static RoutingContext slaRisk() {
        return new RoutingContext(TriggerReason.SLA_RISK, null, null, List.of(), Map.of(), 0, ReasoningListener.NONE);
    }

    public RoutingContext withPendingLoad(Map<String, Integer> pendingLoad) {
        return new RoutingContext(trigger, failedAgentId, failedAgentName, strandedOrders, Map.copyOf(pendingLoad),
            defaultCapacity, listener);
    }

    public RoutingContext withDefaultCapacity(int defaultCapacity) {
        return new RoutingContext(trigger, failedAgentId, failedAgentName, strandedOrders, pendingLoad,
            defaultCapacity, listener);
    }

    public RoutingContext withListener(ReasoningListener listener) {
        return new RoutingContext(trigger, failedAgentId, failedAgentName, strandedOrders, pendingLoad,
            defaultCapacity, listener);
    }

    public boolean isStreaming() {
        return listener != ReasoningListener.NONE;
    }

    public boolean isRecovery() {
        return trigger == TriggerReason.AGENT_OFFLINE;
    }

    public boolean isSlaRisk() {
        return trigger == TriggerReason.SLA_RISK;
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

    /** The agent's own limit, else the fleet default; 0 = no limit. */
    public int capacityOf(Agent agent) {
        return agent.getMaxCapacity() != null && agent.getMaxCapacity() > 0 ? agent.getMaxCapacity() : defaultCapacity;
    }

    /** Room for one more order without going over capacity. */
    public boolean hasRoom(Agent agent) {
        int capacity = capacityOf(agent);
        return capacity <= 0 || effectiveLoad(agent) < capacity;
    }

    /** "4/6" (or just "4" when unlimited), for reasoning text and prompts. */
    public String loadOfCapacity(Agent agent) {
        int capacity = capacityOf(agent);
        return capacity <= 0 ? String.valueOf(effectiveLoad(agent)) : effectiveLoad(agent) + "/" + capacity;
    }
}
