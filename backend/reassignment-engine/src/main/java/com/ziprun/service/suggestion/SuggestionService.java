package com.ziprun.service.suggestion;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import java.util.List;
import java.util.Optional;

/**
 * Suggestion Service Interface: Business logic for reassignment suggestions.
 *
 * Manages suggestion lifecycle:
 * - Create (by routing engine or agentic loop)
 * - Query (by UI for ops approval)
 * - Update (when ops accepts/rejects)
 * - Idempotency checks (prevent duplicate re-plans)
 */
public interface SuggestionService {

    /**
     * Create a reassignment suggestion.
     * Called by routing engine after LLM/rule-based decision.
     *
     * @param suggestion suggestion to persist
     * @return created suggestion
     */
    ReassignmentSuggestion createSuggestion(ReassignmentSuggestion suggestion);

    /**
     * Find suggestion by ID.
     *
     * @param suggestionId suggestion identifier
     * @return suggestion if found
     */
    Optional<ReassignmentSuggestion> findById(String suggestionId);

    /**
     * Find all suggestions.
     *
     * @return all suggestions
     */
    List<ReassignmentSuggestion> findAll();

    /**
     * Find suggestions by status.
     * UI calls this to show pending reassignments.
     *
     * @param status suggestion status (PENDING, ACCEPTED, REJECTED)
     * @return suggestions matching status
     */
    List<ReassignmentSuggestion> findByStatus(SuggestionStatus status);

    /**
     * Find suggestions for a specific order.
     * Used to check if order already has a pending suggestion.
     *
     * @param orderId order identifier
     * @return suggestions for this order
     */
    List<ReassignmentSuggestion> findByOrderId(String orderId);

    /**
     * Update suggestion status (ops approval/rejection).
     * Transitions PENDING → ACCEPTED or REJECTED.
     *
     * @param suggestionId suggestion to update
     * @param newStatus ACCEPTED or REJECTED
     * @return updated suggestion
     * @throws IllegalArgumentException if suggestion not found
     */
    ReassignmentSuggestion updateStatus(String suggestionId, SuggestionStatus newStatus);

    /**
     * Check if order already has a pending offline re-plan suggestion.
     * Used for idempotency: prevent duplicate suggestions when same agent
     * goes offline twice or when multiple agents fail simultaneously.
     *
     * @param orderId order to check
     * @return true if pending AGENT_OFFLINE suggestion exists
     */
    boolean hasPendingOfflineSuggestion(String orderId);
}
