package com.ziprun.domain;

import com.ziprun.exception.InvalidStateException;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Order entity representing a delivery order.
 *
 * Key design decisions:
 * 1. State machine: ASSIGNED → REASSIGNMENT_PENDING → REASSIGNED → DELIVERED
 * 2. pickupZone and dropoffZone are nullable, reserved for Sprint 2 zone-aware routing
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
     * Sprint 2: Zone awareness
     * Pickup location zone (e.g., "KORAMANGALA", "HSR_LAYOUT")
     * Nullable for now; used by ZoneAffinityStrategy in Sprint 2
     */
    @Column(nullable = true)
    private String pickupZone;

    /**
     * Sprint 2: Zone awareness
     * Dropoff location zone
     * Nullable for now; used by ZoneAffinityStrategy in Sprint 2
     */
    @Column(nullable = true)
    private String dropoffZone;

    /**
     * Sprint 3: SLA deadlines
     * When set, a scheduled SLA monitor can publish a re-plan trigger as the
     * deadline approaches (see ADR-5). Nullable; unused in Sprint 1.
     */
    @Column(nullable = true)
    private LocalDateTime slaDeadline;

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
}
