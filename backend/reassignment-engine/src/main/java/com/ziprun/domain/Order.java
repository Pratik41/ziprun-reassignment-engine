package com.ziprun.domain;

import com.ziprun.exception.InvalidStateException;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Order entity representing a delivery order.
 *
 * Key design decisions:
 * 1. State machine: ASSIGNED → REASSIGNMENT_PENDING → REASSIGNED → DELIVERED
 * 2. pickupZone / dropoffZone (Zones ids) feed zone-aware routing; slaDeadline feeds
 *    the SLA monitor. All nullable: an order without them is routed on load alone.
 * 3. Suggestions are queried by orderId via repository (no bidirectional relationship)
 * 4. createdAt tracks when order was assigned
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String id;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false)
    private String assignedAgentId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * Pickup location zone (a Zones id, e.g. "KORAMANGALA"). Nullable.
     * Routing prefers agents in or next to it; the AI sees it too.
     */
    @Column(nullable = true)
    private String pickupZone;

    /**
     * Dropoff location zone. Nullable; see pickupZone.
     */
    @Column(nullable = true)
    private String dropoffZone;

    /**
     * Delivery deadline, set at creation (orders.default-sla-minutes unless given).
     * Nullable. The SLA monitor flags orders close to it and suggests a faster agent.
     */
    @Column(nullable = true)
    private LocalDateTime slaDeadline;

    /**
     * Agent the recommendation engine suggested when the order was created, and
     * whether ops went with it. Both null when no recommendation was shown.
     * Fixed at creation (assignedAgentId moves on reassignment; these don't).
     */
    @Column(nullable = true)
    private String recommendedAgentId;

    @Column(nullable = true)
    private Boolean followedRecommendation;

    /** When the SLA monitor flagged this order as likely to miss slaDeadline (once per order). */
    @Column(nullable = true)
    private LocalDateTime slaAlertedAt;

    /**
     * Moves the order through its state machine; rejects illegal transitions.
     */
    public void transitionTo(OrderStatus next) {
        if (status == next && next == OrderStatus.REASSIGNMENT_PENDING) {
            return; // already pending: re-plan triggers are idempotent
        }
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateException(
                String.format("Order %s cannot move from %s to %s", id, status, next));
        }
        status = next;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = OrderStatus.ASSIGNED;
        }
    }

    // Explicit getters/setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAssignedAgentId() { return assignedAgentId; }
    public void setAssignedAgentId(String assignedAgentId) { this.assignedAgentId = assignedAgentId; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getPickupZone() { return pickupZone; }
    public void setPickupZone(String pickupZone) { this.pickupZone = pickupZone; }

    public String getDropoffZone() { return dropoffZone; }
    public void setDropoffZone(String dropoffZone) { this.dropoffZone = dropoffZone; }

    public LocalDateTime getSlaDeadline() { return slaDeadline; }
    public void setSlaDeadline(LocalDateTime slaDeadline) { this.slaDeadline = slaDeadline; }
    public String getRecommendedAgentId() { return recommendedAgentId; }
    public void setRecommendedAgentId(String recommendedAgentId) { this.recommendedAgentId = recommendedAgentId; }
    public Boolean getFollowedRecommendation() { return followedRecommendation; }
    public void setFollowedRecommendation(Boolean followedRecommendation) { this.followedRecommendation = followedRecommendation; }
    public LocalDateTime getSlaAlertedAt() { return slaAlertedAt; }
    public void setSlaAlertedAt(LocalDateTime slaAlertedAt) { this.slaAlertedAt = slaAlertedAt; }
}
