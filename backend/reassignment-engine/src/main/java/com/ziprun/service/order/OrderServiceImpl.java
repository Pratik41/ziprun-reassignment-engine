package com.ziprun.service.order;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.exception.InvalidStateException;
import com.ziprun.exception.NotFoundException;
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
 * - Create orders with validation (agent must exist and be AVAILABLE)
 * - Query orders by various criteria (status, agent, ID)
 * - Drive the Order state machine (transitions are defined on OrderStatus)
 * - Keep agent load (activeOrderCount) consistent when orders move between agents
 *
 * Controller → OrderService → OrderRepository (clean separation)
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

        Agent agent = getAgent(assignedAgentId);
        if (agent.getStatus() != AgentStatus.AVAILABLE) {
            throw new InvalidStateException(String.format(
                "%s is %s and isn't taking new orders; choose an AVAILABLE agent", agent.getName(), agent.getStatus()));
        }

        Order order = new Order();
        order.setId("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setDescription(description);
        order.setAssignedAgentId(assignedAgentId);
        order.setStatus(OrderStatus.ASSIGNED);
        order.setCreatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);

        agent.assignOrder();

        log.info("Order created: id={}, agent={}, activeOrders={}",
            saved.getId(), assignedAgentId, agent.getActiveOrderCount());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findById(String orderId) {
        return orderRepository.findById(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public Order getById(String orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findAll() {
        return orderRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status);
    }

    @Override
    public Order updateStatus(String orderId, OrderStatus newStatus) {
        log.debug("Updating order status: id={}, newStatus={}", orderId, newStatus);

        Order order = getById(orderId);
        if (newStatus == OrderStatus.REASSIGNED) {
            // REASSIGNED always means "now with a different agent"; that needs a target agent
            throw new InvalidStateException(
                "Use POST /orders/{id}/reassign or accept a suggestion to move an order to REASSIGNED");
        }
        if (newStatus == OrderStatus.ASSIGNED) {
            // Taking a stranded order back is giving that agent work again: they must be AVAILABLE
            Agent agent = getAgent(order.getAssignedAgentId());
            if (agent.getStatus() != AgentStatus.AVAILABLE) {
                throw new InvalidStateException(String.format(
                    "Order %s can't go back to %s: they are %s, not AVAILABLE", orderId, agent.getName(), agent.getStatus()));
            }
        }
        order.transitionTo(newStatus);

        if (newStatus == OrderStatus.DELIVERED) {
            agentRepository.findById(order.getAssignedAgentId()).ifPresent(Agent::releaseOrder);
        }

        log.info("Order status updated: id={}, status={}", orderId, newStatus);
        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findActiveOrdersForAgent(String agentId) {
        return orderRepository.findByAssignedAgentIdAndStatusIn(agentId, OrderStatus.ACTIVE);
    }

    @Override
    public Order markReassignmentPending(String orderId) {
        Order order = getById(orderId);
        order.transitionTo(OrderStatus.REASSIGNMENT_PENDING);
        return order;
    }

    @Override
    public Order reassignToAgent(String orderId, String newAgentId) {
        Order order = getById(orderId);
        Agent newAgent = getAgent(newAgentId);

        if (newAgentId.equals(order.getAssignedAgentId())) {
            throw new InvalidStateException(String.format("Order %s is already assigned to %s", orderId, newAgentId));
        }
        // Only AVAILABLE agents take new orders (BUSY = still delivering, not taking more)
        if (newAgent.getStatus() != AgentStatus.AVAILABLE) {
            throw new InvalidStateException(String.format(
                "%s is %s and isn't taking new orders; choose an AVAILABLE agent", newAgent.getName(), newAgent.getStatus()));
        }

        String oldAgentId = order.getAssignedAgentId();
        order.transitionTo(OrderStatus.REASSIGNED);
        agentRepository.findById(oldAgentId).ifPresent(Agent::releaseOrder);
        newAgent.assignOrder();
        order.setAssignedAgentId(newAgentId);

        log.info("Order reassigned: orderId={}, {} -> {} (new load {})",
            orderId, oldAgentId, newAgentId, newAgent.getActiveOrderCount());
        return order;
    }

    private Agent getAgent(String agentId) {
        return agentRepository.findById(agentId).orElseThrow(() -> new NotFoundException("Agent", agentId));
    }
}
