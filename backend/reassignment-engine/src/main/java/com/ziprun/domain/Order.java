package com.ziprun.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Order entity representing a delivery order.
 *
 * Key design decisions:
 * 1. State machine: ASSIGNED → REASSIGNMENT_PENDING → REASSIGNED → DELIVERED
 * 2. pickupZone and dropoffZone are nullable, reserved for Sprint 2 zone-aware routing
 * 3. One-to-many relationship with ReassignmentSuggestion (orders can have multiple suggestions over time)
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

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ReassignmentSuggestion> suggestions = new ArrayList<>();

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

    public List<ReassignmentSuggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<ReassignmentSuggestion> suggestions) { this.suggestions = suggestions; }
}
