package com.ziprun.repository;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReassignmentSuggestionRepository extends JpaRepository<ReassignmentSuggestion, String> {
    List<ReassignmentSuggestion> findByStatus(SuggestionStatus status);

    List<ReassignmentSuggestion> findByOrderId(String orderId);

    List<ReassignmentSuggestion> findByOrderIdAndStatus(String orderId, SuggestionStatus status);

    List<ReassignmentSuggestion> findByRecommendedAgentIdAndStatus(String agentId, SuggestionStatus status);

    /**
     * Critical for agentic loop idempotency (ADR-8).
     * Before creating a new AGENT_OFFLINE suggestion, check whether one is already
     * PENDING for this order.
     */
    boolean existsByOrderIdAndStatusAndTriggerReason(
            String orderId,
            SuggestionStatus status,
            TriggerReason triggerReason
    );

    /**
     * Number of PENDING suggestions per recommended agent: [agentId, count].
     * Routing adds this to each agent's activeOrderCount so that a batch of
     * stranded orders is spread across agents instead of all landing on the
     * single least-loaded one before ops has accepted anything.
     */
    @Query("select s.recommendedAgentId, count(s) from ReassignmentSuggestion s " +
           "where s.status = com.ziprun.domain.SuggestionStatus.PENDING group by s.recommendedAgentId")
    List<Object[]> countPendingByRecommendedAgent();
}
