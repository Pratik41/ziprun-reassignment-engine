package com.ziprun.domain;

import jakarta.persistence.*;

/**
 * Delivery agent entity.
 *
 * Key design decisions:
 * 1. status (AVAILABLE / BUSY / OFFLINE) is set by ops or the agent; only AVAILABLE
 *    agents are routing candidates. activeOrderCount is the load metric routing uses.
 * 2. Load changes go through assignOrder()/releaseOrder() so the counter can't be
 *    corrupted by ad-hoc arithmetic in services.
 * 3. currentZone and maxCapacity are nullable placeholders for planned zone-affinity
 *    routing and capacity limits; when those land they populate existing columns
 *    instead of needing a migration.
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
     * Zone the agent is currently in. Nullable; input for a planned zone-affinity strategy.
     */
    @Column(nullable = true)
    private String currentZone;

    /**
     * Maximum concurrent orders; null means "no limit configured".
     * Not enforced yet: routing will filter on activeOrderCount < maxCapacity once populated.
     */
    @Column(nullable = true)
    private Integer maxCapacity;

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
}
