package com.ziprun.repository;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByStatus(OrderStatus status);

    List<Order> findByAssignedAgentId(String agentId);

    List<Order> findByAssignedAgentIdAndStatus(String agentId, OrderStatus status);

    /**
     * Orders an agent still owns (ASSIGNED, REASSIGNED, or already REASSIGNMENT_PENDING).
     * Used by the agentic loop to find stranded orders when that agent goes offline.
     */
    List<Order> findByAssignedAgentIdAndStatusInOrderByCreatedAtAscIdAsc(String agentId, Collection<OrderStatus> statuses);

    /**
     * Row lock on the order (SELECT ... FOR UPDATE). Serialises concurrent re-plans
     * of the same order so the idempotency check and the insert happen atomically.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") String id);

    /** SLA monitor: live orders due before the cutoff that haven't been flagged yet. */
    @Query("select o from Order o where o.status in :statuses and o.slaDeadline is not null "
        + "and o.slaDeadline < :cutoff and o.slaAlertedAt is null")
    List<Order> findUnflaggedDueBefore(@Param("statuses") Collection<OrderStatus> statuses,
                                       @Param("cutoff") java.time.LocalDateTime cutoff);

    /** Orders created with a recommendation on screen (Insights: "recommended pick followed"). */
    long countByFollowedRecommendationIsNotNull();

    long countByFollowedRecommendation(Boolean followed);
}
