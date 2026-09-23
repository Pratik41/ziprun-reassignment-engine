package com.ziprun.service.order;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Order Service Implementation: All order business logic lives here.
 *
 * Responsibilities:
 * - Create orders with validation (agent must exist and be available)
 * - Query orders by various criteria (status, agent, ID)
 * - Update order status with state machine validation
 * - Coordinate with repositories for persistence
 *
 * Controller → OrderService → OrderRepository (clean separation)
 *
 * Why this structure?
 * - Controller: "User wants to create an order"
 * - Service: "Create order, validate agent exists, save to DB"
 * - Repository: "Execute JPA query"
 */
@Service
@Transactional
public class OrderServiceImpl implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final OrderRepository orderRepository;
    private final AgentRepository agentRepository;

    public OrderServiceImpl(OrderRepository orderRepository, AgentRepository agentRepository) {
        this.orderRepository = orderRepository;
        this.agentRepository = agentRepository;
    }

    @Override
    public Order createOrder(String description, String assignedAgentId) {
        log.debug("Creating order: description={}, agent={}", description, assignedAgentId);

        // Validation: Check agent exists
        Agent agent = agentRepository.findById(assignedAgentId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Agent not found: " + assignedAgentId
            ));

        // Validation: Agent must not be OFFLINE
        if (agent.getStatus() == AgentStatus.OFFLINE) {
            throw new IllegalArgumentException(
                "Cannot assign order to OFFLINE agent: " + assignedAgentId
            );
        }

        // Create order entity
        Order order = new Order();
        order.setId("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setDescription(description);
        order.setAssignedAgentId(assignedAgentId);
        order.setStatus(OrderStatus.ASSIGNED);
        order.setCreatedAt(LocalDateTime.now());

        // Persist
        Order saved = orderRepository.save(order);
        log.info("Order created: id={}, agent={}", saved.getId(), assignedAgentId);

        return saved;
    }

    @Override
    public Optional<Order> findById(String orderId) {
        log.debug("Fetching order: {}", orderId);
        return orderRepository.findById(orderId);
    }

    @Override
    public List<Order> findAll() {
        log.debug("Fetching all orders");
        return orderRepository.findAll();
    }

    @Override
    public List<Order> findByStatus(OrderStatus status) {
        log.debug("Fetching orders by status: {}", status);
        return orderRepository.findByStatus(status);
    }

    @Override
    public Order updateStatus(String orderId, OrderStatus newStatus) {
        log.debug("Updating order status: id={}, newStatus={}", orderId, newStatus);

        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        // Validate state transition
        validateStatusTransition(order.getStatus(), newStatus);

        // Update
        order.setStatus(newStatus);
        Order updated = orderRepository.save(order);

        log.info("Order status updated: id={}, status={}", orderId, newStatus);
        return updated;
    }

    @Override
    public List<Order> findByAssignedAgentId(String agentId) {
        log.debug("Fetching orders for agent: {}", agentId);
        return orderRepository.findByAssignedAgentId(agentId);
    }

    /**
     * Validate order status transitions (state machine).
     * ASSIGNED → REASSIGNMENT_PENDING → REASSIGNED → DELIVERED (only valid path)
     *
     * @throws IllegalArgumentException if transition is invalid
     */
    private void validateStatusTransition(OrderStatus current, OrderStatus next) {
        boolean valid = switch (current) {
            case ASSIGNED -> next == OrderStatus.REASSIGNMENT_PENDING || next == OrderStatus.DELIVERED;
            case REASSIGNMENT_PENDING -> next == OrderStatus.REASSIGNED || next == OrderStatus.ASSIGNED;
            case REASSIGNED -> next == OrderStatus.DELIVERED;
            case DELIVERED -> false; // Terminal state
        };

        if (!valid) {
            throw new IllegalArgumentException(
                String.format("Invalid status transition: %s → %s", current, next)
            );
        }
    }
}
