package com.ziprun.service.order;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import java.util.List;
import java.util.Optional;

/**
 * Order Service Interface: Business logic for order management.
 *
 * Separates HTTP concerns (controller) from business logic (service).
 * Controller calls these methods and handles HTTP responses.
 * Service focuses on: order creation, status transitions, reassignment, querying.
 */
public interface OrderService {

    /**
     * Create a new order pre-assigned to an agent.
     * Simulates morning manual assignment workflow.
     *
     * @throws com.ziprun.exception.NotFoundException if the agent doesn't exist
     * @throws com.ziprun.exception.InvalidStateException if the agent is not AVAILABLE
     */
    Order createOrder(String description, String assignedAgentId);

    Optional<Order> findById(String orderId);

    /**
     * @throws com.ziprun.exception.NotFoundException if the order doesn't exist
     */
    Order getById(String orderId);

    List<Order> findAll();

    List<Order> findByStatus(OrderStatus status);

    /**
     * Update order status, enforcing the OrderStatus state machine.
     * Moving to DELIVERED releases the agent's load.
     *
     * @throws com.ziprun.exception.InvalidStateException on an illegal transition
     */
    Order updateStatus(String orderId, OrderStatus newStatus);

    /**
     * Orders the agent still owns (ASSIGNED, REASSIGNED, REASSIGNMENT_PENDING).
     * Used by the agentic loop when the agent goes offline.
     */
    List<Order> findActiveOrdersForAgent(String agentId);

    /**
     * Flag an order as stranded. Idempotent: already-pending orders are left as-is.
     */
    Order markReassignmentPending(String orderId);

    /**
     * Move an order to a new agent: releases the old agent's load, adds to the
     * new agent's load, sets status REASSIGNED. Used when ops accepts a
     * suggestion and for manual overrides.
     *
     * @throws com.ziprun.exception.InvalidStateException if the order is DELIVERED,
     *         the new agent isn't AVAILABLE, or it's the same agent
     */
    Order reassignToAgent(String orderId, String newAgentId);
}
