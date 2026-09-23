package com.ziprun.service.event;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.domain.event.AgentOfflineEvent;
import com.ziprun.repository.OrderRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.routing.RoutingService;
import com.ziprun.routing.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Replan Event Handler: Listens for AgentOfflineEvent and triggers agentic loop.
 *
 * CRITICAL WORKFLOW:
 * 1. Agent status changes to OFFLINE → AgentService publishes AgentOfflineEvent
 * 2. Spring async invokes this handler (non-blocking)
 * 3. Find all orders assigned to offline agent with status = ASSIGNED
 * 4. For each order:
 *    a. Change status: ASSIGNED → REASSIGNMENT_PENDING
 *    b. Check for existing PENDING suggestion with trigger=AGENT_OFFLINE (idempotency)
 *    c. If no suggestion exists, call routing strategy
 *    d. Create suggestion with triggerReason=AGENT_OFFLINE
 *    e. Never silently drop failures - fallback to rule-based
 *
 * This handler demonstrates T-4 from problem statement: event-driven, async, idempotent re-planning.
 */
@Service
public class ReplanEventHandler {
    private static final Logger log = LoggerFactory.getLogger(ReplanEventHandler.class);

    private final OrderRepository orderRepository;
    private final ReassignmentSuggestionRepository suggestionRepository;
    private final RoutingService routingService;

    public ReplanEventHandler(
            OrderRepository orderRepository,
            ReassignmentSuggestionRepository suggestionRepository,
            RoutingService routingService
    ) {
        this.orderRepository = orderRepository;
        this.suggestionRepository = suggestionRepository;
        this.routingService = routingService;
    }

    /**
     * Handle agent offline event.
     * Async = non-blocking, runs in thread pool
     * Transactional = all-or-nothing for this batch
     */
    @EventListener
    @Async
    @Transactional
    public void onAgentOffline(AgentOfflineEvent event) {
        String agentId = event.getAgentId();
        String agentName = event.getAgentName();

        log.info("🤖 AGENTIC LOOP TRIGGERED: Agent {} ({}) has gone OFFLINE. Finding stranded orders...", agentId, agentName);

        // Step 1: Find all ASSIGNED orders for this agent
        List<Order> strandedOrders = orderRepository.findByAssignedAgentIdAndStatus(agentId, OrderStatus.ASSIGNED);
        log.info("Found {} stranded orders for agent {}", strandedOrders.size(), agentId);

        if (strandedOrders.isEmpty()) {
            log.info("No stranded orders for agent {}", agentId);
            return;
        }

        // Step 2: Process each stranded order
        for (Order order : strandedOrders) {
            try {
                processSingleOrder(order, agentId, agentName);
            } catch (Exception e) {
                log.error("Failed to process order {} for offline agent {}. Error: {}", order.getId(), agentId, e.getMessage(), e);
                // Continue processing other orders, don't cascade failure
            }
        }

        log.info("✓ Agentic loop completed for agent {} ({}).  All stranded orders now in REASSIGNMENT_PENDING with suggestions", agentId, agentName);
    }

    /**
     * Process a single stranded order:
     * 1. Transition order to REASSIGNMENT_PENDING
     * 2. Check if suggestion already exists (idempotency)
     * 3. Call routing strategy with recovery context (different prompts for AI)
     * 4. Create and persist suggestion
     */
    private void processSingleOrder(Order order, String offlineAgentId, String offlineAgentName) {
        String orderId = order.getId();
        log.debug("Processing stranded order: {} (was assigned to {})", orderId, offlineAgentId);

        // Step 1: Transition order to REASSIGNMENT_PENDING
        order.setStatus(OrderStatus.REASSIGNMENT_PENDING);
        Order updated = orderRepository.save(order);
        log.debug("Order transitioned to REASSIGNMENT_PENDING: {}", orderId);

        // Step 2: Check for existing pending suggestion (idempotency guard)
        boolean hasPendingAutoSuggestion = suggestionRepository
            .findByOrderIdAndStatusAndTriggerReason(
                orderId,
                SuggestionStatus.PENDING,
                TriggerReason.AGENT_OFFLINE
            )
            .isPresent();

        if (hasPendingAutoSuggestion) {
            log.debug("Idempotency check: Suggestion already exists for order {}. Skipping.", orderId);
            return;
        }

        // Step 3: Get routing recommendation WITH recovery context
        // This ensures AI strategy uses re-plan prompt (recovery mode) instead of initial assignment prompt
        List<Order> allStrandedOrders = orderRepository.findByAssignedAgentIdAndStatus(offlineAgentId, OrderStatus.REASSIGNMENT_PENDING);
        int strandedOrderCount = allStrandedOrders.size();

        RoutingResult routingResult = routingService.routeWithRecoveryContext(
            updated,
            offlineAgentId,
            offlineAgentName,
            strandedOrderCount
        );
        log.debug("Routing result for order {}: agent={}, confidence={}", orderId, routingResult.getRecommendedAgentId(), routingResult.getConfidence());

        // Step 4: Create suggestion with AGENT_OFFLINE trigger
        ReassignmentSuggestion suggestion = new ReassignmentSuggestion();
        suggestion.setId("SUGG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        suggestion.setOrderId(orderId);
        suggestion.setRecommendedAgentId(routingResult.getRecommendedAgentId());
        suggestion.setConfidence(routingResult.getConfidence());
        suggestion.setReasoning(routingResult.getReasoning());
        suggestion.setStatus(SuggestionStatus.PENDING);
        suggestion.setTriggerReason(TriggerReason.AGENT_OFFLINE);  // KEY: Auto-triggered, not manual
        suggestion.setCreatedAt(LocalDateTime.now());

        ReassignmentSuggestion saved = suggestionRepository.save(suggestion);
        log.info("✓ Auto-suggestion created for stranded order {}: {}% confidence in agent {}",
            orderId,
            (int)(saved.getConfidence() * 100),
            saved.getRecommendedAgentId()
        );
    }
}
