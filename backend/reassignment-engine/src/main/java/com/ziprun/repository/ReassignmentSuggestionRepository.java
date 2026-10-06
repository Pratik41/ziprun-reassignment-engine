package com.ziprun.repository;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReassignmentSuggestionRepository extends JpaRepository<ReassignmentSuggestion, String> {
    List<ReassignmentSuggestion> findByStatus(SuggestionStatus status);

    Page<ReassignmentSuggestion> findByStatus(SuggestionStatus status, Pageable pageable);

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

    /** Insights: [source, status, count, sum(confidence), count(routingMillis), sum(routingMillis)]. */
    @Query("select s.source, s.status, count(s), sum(s.confidence), count(s.routingMillis), sum(s.routingMillis) "
         + "from ReassignmentSuggestion s group by s.source, s.status")
    List<Object[]> statsBySourceAndStatus();

    /** Insights p95: [source, routingMillis] of the latest timed suggestions, newest first. */
    @Query("select s.source, s.routingMillis from ReassignmentSuggestion s where s.routingMillis is not null "
         + "order by s.createdAt desc")
    List<Object[]> recentRoutingTimes(Pageable page);

    /*
     * Withdrawing suggestions (PENDING -> EXPIRED) is one conditional UPDATE, not load-modify-save:
     * atomic, and two withdrawals of the same rows (ops' "Keep" and a background re-balance at the
     * same moment) simply both succeed. Bumping the version means an accept that read the row before
     * the withdrawal still fails its optimistic-lock check instead of accepting an expired suggestion.
     * Bulk updates skip entity listeners, so callers notify live updates themselves.
     */

    @Modifying(flushAutomatically = true)
    @Query("update ReassignmentSuggestion s set s.status = com.ziprun.domain.SuggestionStatus.EXPIRED, "
         + "s.decidedAt = :now, s.version = s.version + 1 "
         + "where s.orderId = :orderId and s.status = com.ziprun.domain.SuggestionStatus.PENDING")
    int expirePendingForOrder(@Param("orderId") String orderId, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("update ReassignmentSuggestion s set s.status = com.ziprun.domain.SuggestionStatus.EXPIRED, "
         + "s.decidedAt = :now, s.version = s.version + 1 "
         + "where s.recommendedAgentId = :agentId and s.status = com.ziprun.domain.SuggestionStatus.PENDING")
    int expirePendingRecommending(@Param("agentId") String agentId, @Param("now") LocalDateTime now);
}
