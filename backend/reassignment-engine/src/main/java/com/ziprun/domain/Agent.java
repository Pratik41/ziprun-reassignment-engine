package com.ziprun.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Delivery agent entity.
 *
 * Key design decisions:
 * 1. status (AVAILABLE / BUSY / OFFLINE) is set by ops or the agent; only AVAILABLE
 *    agents are routing candidates. activeOrderCount is the load metric routing uses.
 * 2. Load changes go through assignOrder()/releaseOrder() so the counter can't be
 *    corrupted by ad-hoc arithmetic in services.
 * 3. currentZone feeds zone-aware routing; maxCapacity caps effective load (null =
 *    the fleet default, agents.default-max-capacity). Both editable from the Fleet page.
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
     * Zone the agent is currently in (a Zones id). Nullable = unknown location.
     */
    @Column(nullable = true)
    private String currentZone;

    /**
     * Most orders (active + queued suggestions) routing will give this agent;
     * null = use the fleet default (agents.default-max-capacity).
     */
    @Column(nullable = true)
    private Integer maxCapacity;

    /**
     * When the agent's phone app last checked in. Null means no app is reporting
     * (or ops changed the status by hand since), so the heartbeat monitor ignores them.
     */
    @Column(nullable = true)
    private LocalDateTime lastHeartbeatAt;

    /** Why the system last changed this agent's status, e.g. an automatic offline. Cleared by manual changes. */
    @Column(nullable = true, length = 200)
    private String statusNote;

    /**
     * Only AVAILABLE agents are offered new orders (BUSY = online but not taking more).
     */
    public boolean canTakeOrders() {
        return status == AgentStatus.AVAILABLE;
    }

    public void assignOrder() {
        activeOrderCount = (activeOrderCount == null ? 0 : activeOrderCount) + 1;
    }

    public void releaseOrder() {
        activeOrderCount = Math.max(0, (activeOrderCount == null ? 0 : activeOrderCount) - 1);
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

    public Integer getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Integer maxCapacity) { this.maxCapacity = maxCapacity; }

    public LocalDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }

    public String getStatusNote() { return statusNote; }
    public void setStatusNote(String statusNote) { this.statusNote = statusNote; }
}
