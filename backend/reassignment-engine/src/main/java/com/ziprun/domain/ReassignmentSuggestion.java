package com.ziprun.domain;

import com.ziprun.service.live.ChangeTracker;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Reassignment suggestion: a recommendation to move an order from one agent to another.
 *
 * Key design decisions:
 * 1. Stores AI's confidence score (0.0-1.0) and plain-English reasoning for ops
 * 2. triggerReason distinguishes agentic loop (AGENT_OFFLINE) from manual requests (INITIAL)
 * 3. Status tracks: PENDING (waiting for ops decision) → ACCEPTED, REJECTED or EXPIRED
 * 4. Queryable: ReplanEventHandler checks for existing PENDING AGENT_OFFLINE suggestions (idempotency)
 *
 * This entity is central to the entire system:
 * - Created on demand (POST /orders/{id}/suggest, triggerReason INITIAL)
 * - Created by the agentic loop when agent availability changes (triggerReason AGENT_OFFLINE)
 * - Decided by ops in the UI (PATCH /suggestions/{id}: accept / reject)
 */
@Entity
@EntityListeners(ChangeTracker.class) // pushes "data changed" to open consoles (GET /events)
@Table(name = "reassignment_suggestions")
public class ReassignmentSuggestion {

    @Id
    private String id;

    /**
     * Optimistic locking: every update checks the row still has the version it was read with.
     * Two transactions changing the same row at once (a heartbeat and a status change, two
     * accepts loading the same agent's order count) can't silently overwrite each other:
     * the second gets a conflict (409) instead of a lost update.
     */
    @Version
    @JsonIgnore
    private Long version;

    @Column(name = "order_id", nullable = false)
    private String orderId;

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
    @Column(nullable = false, length = 2000)
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

    /**
     * Which strategy actually produced this suggestion, e.g. "ai", "rule-based",
     * or "rule-based (AI fallback: TIMEOUT)". Lets ops see
     * when the AI was bypassed instead of the fallback being invisible.
     */
    @Column(nullable = true)
    private String source;

    /** How long routing took to produce this (ms, including any AI calls). Null for older rows. */
    @Column(nullable = true)
    private Long routingMillis;

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

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Long getRoutingMillis() { return routingMillis; }
    public void setRoutingMillis(Long routingMillis) { this.routingMillis = routingMillis; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
}
