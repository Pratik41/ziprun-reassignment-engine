package com.ziprun.service.agent;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Agent Service Interface: Business logic for agent management.
 *
 * Manages agent lifecycle:
 * - Query agents (for routing, for UI roster)
 * - Update agent status (AVAILABLE → OFFLINE triggers agentic loop)
 * - Heartbeats from agents' phone apps, and automatic OFFLINE when they stop
 *
 * Agent load (activeOrderCount) is changed only via Agent.assignOrder()/releaseOrder(),
 * called by OrderService when orders move between agents.
 */
public interface AgentService {

    Optional<Agent> findById(String agentId);

    List<Agent> findAll();

    /**
     * @param status agent status (AVAILABLE, BUSY, OFFLINE)
     */
    List<Agent> findByStatus(AgentStatus status);

    /**
     * Manual status change by ops.
     *
     * Publishes the matching availability event (OFFLINE / BUSY / AVAILABLE) for
     * the re-planning loop, after this transaction commits. Refuses to take the
     * last AVAILABLE agent off duty. Clears any automatic status note and pauses
     * heartbeat monitoring until the agent's app reports again, so a manual
     * decision isn't immediately overridden by a stale heartbeat.
     *
     * @throws com.ziprun.exception.NotFoundException if agent not found
     * @throws com.ziprun.exception.InvalidStateException if this would leave no AVAILABLE agent
     */
    Agent updateStatus(String agentId, AgentStatus newStatus);

    /**
     * Records a heartbeat from the agent's phone app (arms heartbeat monitoring).
     *
     * @throws com.ziprun.exception.NotFoundException if agent not found
     */
    Agent heartbeat(String agentId);

    /**
     * Heartbeat monitor: mark an on-duty agent OFFLINE because their app stopped
     * reporting. This is a fact, not a request, so the last-AVAILABLE guardrail
     * does not apply. No-op if a fresh heartbeat arrived in the meantime.
     *
     * @return true if the agent was marked OFFLINE
     */
    boolean markOfflineIfSilentSince(String agentId, LocalDateTime cutoff);

    Agent save(Agent agent);
}
