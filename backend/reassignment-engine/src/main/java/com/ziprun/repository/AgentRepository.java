package com.ziprun.repository;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {
    List<Agent> findByStatus(AgentStatus status);

    Page<Agent> findByStatus(AgentStatus status, Pageable pageable);

    List<Agent> findByStatusIn(List<AgentStatus> statuses);

    /** On-duty agents whose app has gone quiet since the cutoff (heartbeat monitor). */
    List<Agent> findByStatusInAndLastHeartbeatAtBefore(Collection<AgentStatus> statuses, LocalDateTime cutoff);

    /**
     * Locks the matching rows (SELECT ... FOR UPDATE) so two concurrent status
     * changes can't both pass the "keep one agent AVAILABLE" check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Agent a where a.status = :status")
    List<Agent> findByStatusForUpdate(@Param("status") AgentStatus status);
}
