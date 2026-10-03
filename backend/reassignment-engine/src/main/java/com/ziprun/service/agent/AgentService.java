package com.ziprun.service.agent;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import java.util.List;
import java.util.Optional;

/**
 * Agent Service Interface: Business logic for agent management.
 *
 * Manages agent lifecycle:
 * - Query agents (for routing, for UI roster)
 * - Update agent status (AVAILABLE → OFFLINE triggers agentic loop)
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
     * Update agent status.
     *
     * When status changes to OFFLINE, an AgentOfflineEvent is published to
     * trigger the agentic re-planning loop (after this transaction commits).
     *
     * @throws com.ziprun.exception.NotFoundException if agent not found
     */
    Agent updateStatus(String agentId, AgentStatus newStatus);

    Agent save(Agent agent);
}
