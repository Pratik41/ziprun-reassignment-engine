package com.ziprun.domain;

import jakarta.persistence.*;

/**
 * Delivery agent entity.
 *
 * Key design decisions:
 * 1. currentZone field is nullable and marked for future use (Sprint 2)
 * 2. Status changes (especially to OFFLINE) publish events to decouple from controllers
 * 3. activeOrderCount tracks capacity for routing decisions
 */
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AgentStatus status;

    @Column(nullable = false)
    private Integer activeOrderCount;

    /**
     * Sprint 2: Zone awareness
     * Tracks which zone the agent is currently in
     * Nullable for now; used by ZoneAffinityStrategy when added
     */
    @Column(nullable = true)
    private String currentZone;

    /**
     * Get agent availability based on status and capacity
     */
    public boolean isAvailable() {
        return status == AgentStatus.AVAILABLE || status == AgentStatus.BUSY;
    }

    /**
     * Used by routing strategies to make recommendations
     */
    public int getLoad() {
        return activeOrderCount != null ? activeOrderCount : 0;
    }

    // Explicit getters/setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public AgentStatus getStatus() { return status; }
    public void setStatus(AgentStatus status) { this.status = status; }

    public Integer getActiveOrderCount() { return activeOrderCount; }
    public void setActiveOrderCount(Integer activeOrderCount) { this.activeOrderCount = activeOrderCount; }

    public String getCurrentZone() { return currentZone; }
    public void setCurrentZone(String currentZone) { this.currentZone = currentZone; }
}
