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
 * - Update agent workload (increment/decrement active order count)
 */
public interface AgentService {

    /**
     * Find agent by ID.
     *
     * @param agentId agent identifier
     * @return agent if found
     */
    Optional<Agent> findById(String agentId);

    /**
     * List all agents.
     *
     * @return all agents
     */
    List<Agent> findAll();

    /**
     * Find agents by status.
     * Routing engine calls this to find available agents.
     *
     * @param status agent status (AVAILABLE, BUSY, OFFLINE)
     * @return agents matching status
     */
    List<Agent> findByStatus(AgentStatus status);

    /**
     * Update agent status.
     * AVAILABLE, BUSY, OFFLINE status changes.
     *
     * When status changes to OFFLINE, an event is published to trigger
     * the agentic re-planning loop.
     *
     * @param agentId agent to update
     * @param newStatus new agent status
     * @return updated agent
     * @throws IllegalArgumentException if agent not found
     */
    Agent updateStatus(String agentId, AgentStatus newStatus);

    /**
     * Increment active order count.
     * Called when an order is assigned to an agent.
     *
     * @param agentId agent to update
     * @throws IllegalArgumentException if agent not found
     */
    void incrementOrderCount(String agentId);

    /**
     * Decrement active order count.
     * Called when an order is delivered or reassigned away.
     *
     * @param agentId agent to update
     * @throws IllegalArgumentException if agent not found
     */
    void decrementOrderCount(String agentId);

    /**
     * Save a new agent.
     *
     * @param agent agent to save
     * @return saved agent
     */
    Agent save(Agent agent);
}
