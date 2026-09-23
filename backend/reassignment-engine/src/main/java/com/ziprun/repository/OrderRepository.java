package com.ziprun.repository;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    List<Order> findByStatus(OrderStatus status);

    List<Order> findByAssignedAgentId(String agentId);

    List<Order> findByAssignedAgentIdAndStatus(String agentId, OrderStatus status);
}
