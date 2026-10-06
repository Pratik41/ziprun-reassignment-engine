package com.ziprun.service.order;

import com.ziprun.domain.Activity;
import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.exception.InvalidStateException;
import com.ziprun.exception.NotFoundException;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.OrderRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.routing.Zones;
import org.springframework.beans.factory.annotation.Value;
import com.ziprun.service.activity.ActivityService;
import com.ziprun.service.live.ChangeTracker;
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
    private final ReassignmentSuggestionRepository suggestionRepository;
    private final ActivityService activity;
    private final int defaultSlaMinutes;
    private final ChangeTracker changes;

    public OrderServiceImpl(OrderRepository orderRepository, AgentRepository agentRepository,
                            ReassignmentSuggestionRepository suggestionRepository, ActivityService activity,
                            ChangeTracker changes,
                            @Value("${orders.default-sla-minutes:120}") int defaultSlaMinutes) {
        this.orderRepository = orderRepository;
        this.agentRepository = agentRepository;
        this.suggestionRepository = suggestionRepository;
        this.activity = activity;
        this.defaultSlaMinutes = defaultSlaMinutes;
        this.changes = changes;
    }

    @Override
    public Order createOrder(NewOrder spec) {
        String description = spec.description();
        String assignedAgentId = spec.assignedAgentId();
        String recommendedAgentId = spec.recommendedAgentId();
        log.debug("Creating order: description={}, agent={}", description, assignedAgentId);
        String pickupZone = zoneOrNull(spec.pickupZone(), "pickupZone");
        String dropoffZone = zoneOrNull(spec.dropoffZone(), "dropoffZone");
        int slaMinutes = spec.slaMinutes() == null ? defaultSlaMinutes : spec.slaMinutes();
        if (slaMinutes < 0) {
            throw new IllegalArgumentException("slaMinutes must be 0 (no deadline) or more");
        }

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
        order.setPickupZone(pickupZone);
        order.setDropoffZone(dropoffZone);
        if (slaMinutes > 0) {
            order.setSlaDeadline(order.getCreatedAt().plusMinutes(slaMinutes));
        }
        if (recommendedAgentId != null && !recommendedAgentId.isBlank()) {
            order.setRecommendedAgentId(recommendedAgentId);
            order.setFollowedRecommendation(recommendedAgentId.equals(assignedAgentId));
        }
        Order saved = orderRepository.save(order);

        agent.assignOrder();

        log.info("Order created: id={}, agent={}, activeOrders={}",
            saved.getId(), assignedAgentId, agent.getActiveOrderCount());
        activity.record(Activity.Type.ORDER_CREATED, Activity.Actor.OPS,
            String.format("%s created for %s%s: %s", saved.getId(), agent.getName(),
                recommendationNote(saved), description),
            saved.getId(), assignedAgentId, null);
        return saved;
    }

    private static String zoneOrNull(String zone, String field) {
        if (zone == null || zone.isBlank()) {
            return null;
        }
        if (!Zones.isKnown(zone)) {
            throw new IllegalArgumentException("Unknown " + field + " '" + zone + "'. See GET /config for the list");
        }
        return zone;
    }

    @Override
    public Optional<Order> claimSlaAlert(String orderId) {
        // Row lock: two monitor runs (or instances) can't both alert the same order
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getSlaAlertedAt() != null
            || (order.getStatus() != OrderStatus.ASSIGNED && order.getStatus() != OrderStatus.REASSIGNED)) {
            return Optional.empty();
        }
        order.setSlaAlertedAt(LocalDateTime.now());
        return Optional.of(order);
    }

    private String recommendationNote(Order order) {
        if (order.getFollowedRecommendation() == null) {
            return "";
        }
        if (order.getFollowedRecommendation()) {
            return " (recommended pick)";
        }
        String recommended = agentRepository.findById(order.getRecommendedAgentId())
            .map(Agent::getName).orElse(order.getRecommendedAgentId());
        return " (recommendation was " + recommended + ")";
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
            Agent agent = getAgent(order.getAssignedAgentId());
            agent.releaseOrder();
            // Nothing left to decide: withdraw any open suggestion for it (e.g. an SLA one)
            if (suggestionRepository.expirePendingForOrder(orderId, LocalDateTime.now()) > 0) {
                changes.afterCommit("suggestions");
            }
            activity.record(Activity.Type.ORDER_DELIVERED, Activity.Actor.OPS,
                String.format("%s delivered by %s", orderId, agent.getName()), orderId, agent.getId(), null);
        }

        log.info("Order status updated: id={}, status={}", orderId, newStatus);
        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findActiveOrdersForAgent(String agentId) {
        // oldest first: they've waited longest, and re-plans are deterministic (batch balancing depends on order)
        return orderRepository.findByAssignedAgentIdAndStatusInOrderByCreatedAtAscIdAsc(agentId, OrderStatus.ACTIVE);
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
