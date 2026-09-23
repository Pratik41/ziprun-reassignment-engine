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
 * Service focuses on: order creation, status transitions, querying.
 */
public interface OrderService {

    /**
     * Create a new order pre-assigned to an agent.
     * Simulates morning manual assignment workflow.
     *
     * @param description order description/type
     * @param assignedAgentId agent to assign order to
     * @return created order
     * @throws IllegalArgumentException if agent doesn't exist or is OFFLINE
     */
    Order createOrder(String description, String assignedAgentId);

    /**
     * Find order by ID.
     *
     * @param orderId order identifier
     * @return order if found
     */
    Optional<Order> findById(String orderId);

    /**
     * List all orders.
     *
     * @return all orders
     */
    List<Order> findAll();

    /**
     * List orders by status.
     * Used by UI to fetch pending reassignments, assigned orders, etc.
     *
     * @param status order status to filter by
     * @return orders matching status
     */
    List<Order> findByStatus(OrderStatus status);

    /**
     * Update order status.
     * Validates state transitions (e.g., can't go from DELIVERED back to ASSIGNED).
     *
     * @param orderId order to update
     * @param newStatus new status
     * @return updated order
     * @throws IllegalArgumentException if order not found or invalid transition
     */
    Order updateStatus(String orderId, OrderStatus newStatus);

    /**
     * Find all orders assigned to an agent.
     * Used by agentic loop when agent goes offline.
     *
     * @param agentId agent identifier
     * @return all orders currently assigned to this agent
     */
    List<Order> findByAssignedAgentId(String agentId);
}
