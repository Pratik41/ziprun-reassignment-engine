package com.ziprun.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Reassignment suggestion: a recommendation to move an order from one agent to another.
 *
 * Key design decisions:
 * 1. Stores AI's confidence score (0.0-1.0) and plain-English reasoning for ops
 * 2. triggerReason distinguishes agentic loop (AGENT_OFFLINE) from manual requests (INITIAL)
 * 3. Status tracks: PENDING (waiting for ops decision) → ACCEPTED or REJECTED
 * 4. Queryable: ReplanEventHandler checks for existing PENDING AGENT_OFFLINE suggestions (idempotency)
 *
 * This entity is central to the entire system:
 * - Created by T-2 (routing engine on-demand)
 * - Created by T-4 (agentic loop after agent offline)
 * - Updated by ops interface (accept/reject in T-5)
 */
@Entity
@Table(name = "reassignment_suggestions")
public class ReassignmentSuggestion {

    @Id
    private String id;

    @Column(nullable = false)
    private String orderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false)
    private Order order;

    @Column(nullable = false)
    private String recommendedAgentId;

    /**
     * AI's confidence in this recommendation (0.0 to 1.0)
     * Used by UI to show color-coded confidence
     * Rule-based strategy may return 1.0 (certain) or 0.7 (fallback)
     */
    @Column(nullable = false)
    private Double confidence;

    /**
     * Plain-English explanation from AI/rule-based strategy
     * Ops reads this verbatim to make decision
     * Examples:
     *   - "Rahul is AVAILABLE and closest to pickup zone"
     *   - "Ananya has capacity; next best alternative given Rahul overloaded"
     */
    @Column(nullable = false, length = 1000)
    private String reasoning;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private SuggestionStatus status;

    /**
     * Why was this suggestion created?
     * INITIAL: Manual request (ops clicked "suggest")
     * AGENT_OFFLINE: Agentic loop detected offline agent
     *
     * Used to show re-plan badge in UI
     */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TriggerReason triggerReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = true)
    private LocalDateTime decidedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = SuggestionStatus.PENDING;
        }
    }

    // Explicit getters/setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }

    public String getRecommendedAgentId() { return recommendedAgentId; }
    public void setRecommendedAgentId(String recommendedAgentId) { this.recommendedAgentId = recommendedAgentId; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public SuggestionStatus getStatus() { return status; }
    public void setStatus(SuggestionStatus status) { this.status = status; }

    public TriggerReason getTriggerReason() { return triggerReason; }
    public void setTriggerReason(TriggerReason triggerReason) { this.triggerReason = triggerReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
}
