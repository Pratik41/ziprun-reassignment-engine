package com.ziprun.repository;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReassignmentSuggestionRepository extends JpaRepository<ReassignmentSuggestion, String> {
    List<ReassignmentSuggestion> findByStatus(SuggestionStatus status);

    List<ReassignmentSuggestion> findByOrderId(String orderId);

    /**
     * Critical for agentic loop idempotency (ADR-8)
     * Before creating a new AGENT_OFFLINE suggestion, check if one already exists
     * for this order that's still PENDING
     */
    Optional<ReassignmentSuggestion> findByOrderIdAndStatusAndTriggerReason(
            String orderId,
            SuggestionStatus status,
            TriggerReason triggerReason
    );

    /**
     * Get all pending suggestions (ops needs to review)
     */
    List<ReassignmentSuggestion> findByStatusOrderByCreatedAtDesc(SuggestionStatus status);

    /**
     * Get suggestions created from agentic loop (for badge display in UI)
     */
    List<ReassignmentSuggestion> findByTriggerReasonAndStatus(TriggerReason triggerReason, SuggestionStatus status);
}
