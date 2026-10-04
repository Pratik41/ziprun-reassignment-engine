package com.ziprun.service.suggestion;

import com.ziprun.domain.Order;
import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.routing.RoutingResult;
import java.util.List;
import java.util.Optional;

/**
 * Suggestion Service Interface: Business logic for reassignment suggestions.
 *
 * Manages suggestion lifecycle:
 * - Create (by the HTTP suggest endpoint or the agentic loop)
 * - Query (by UI for ops approval)
 * - Decide (ops accepts/rejects; accepting reassigns the order) - the human checkpoint
 * - Idempotency checks (prevent duplicate re-plans)
 */
public interface SuggestionService {

    /**
     * Persist a PENDING suggestion built from a routing result.
     */
    ReassignmentSuggestion createSuggestion(String orderId, RoutingResult result, TriggerReason triggerReason);

    /**
     * Agentic-loop variant: creates an AGENT_OFFLINE suggestion unless one is
     * already PENDING for the order. The check and the insert run under a row
     * lock on the order, so two concurrent re-plans can't both insert.
     *
     * @return the new suggestion, or empty if one already existed
     */
    Optional<ReassignmentSuggestion> createReplanSuggestionIfAbsent(String orderId, RoutingResult result);

    Optional<ReassignmentSuggestion> findById(String suggestionId);

    List<ReassignmentSuggestion> findAll();

    List<ReassignmentSuggestion> findByStatus(SuggestionStatus status);

    List<ReassignmentSuggestion> findByOrderId(String orderId);

    /**
     * Ops decision on a PENDING suggestion.
     * ACCEPTED: reassigns the order to the recommended agent and rejects any
     *           other PENDING suggestions for that order, in one transaction.
     * REJECTED: marks the suggestion; the order stays REASSIGNMENT_PENDING so
     *           ops can request another suggestion or reassign manually.
     *
     * @throws com.ziprun.exception.NotFoundException if the suggestion doesn't exist
     * @throws com.ziprun.exception.InvalidStateException if not PENDING, or the
     *         recommended agent has since gone OFFLINE
     */
    ReassignmentSuggestion updateStatus(String suggestionId, SuggestionStatus newStatus);

    /**
     * True if a PENDING suggestion with triggerReason=AGENT_OFFLINE exists for the order.
     */
    boolean hasPendingOfflineSuggestion(String orderId);

    /**
     * Withdraws (EXPIRED) every PENDING suggestion that recommends this agent.
     * Called by the agentic loop when the agent goes OFFLINE.
     *
     * @return ids of the orders whose suggestion was withdrawn
     */
    List<String> expirePendingRecommending(String agentId);

    /**
     * Ops decision: the order's original agent is back, keep it with them.
     * Order REASSIGNMENT_PENDING -> ASSIGNED and its PENDING suggestions EXPIRED,
     * in one transaction.
     *
     * @throws com.ziprun.exception.InvalidStateException if the agent is still OFFLINE
     *         or the order isn't REASSIGNMENT_PENDING
     */
    Order keepWithCurrentAgent(String orderId);
}
