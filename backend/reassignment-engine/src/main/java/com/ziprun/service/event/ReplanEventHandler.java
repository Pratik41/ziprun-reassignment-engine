package com.ziprun.service.event;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.event.AgentAvailableEvent;
import com.ziprun.domain.event.AgentBusyEvent;
import com.ziprun.domain.event.AgentOfflineEvent;
import com.ziprun.exception.StaleRecommendationException;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingService;
import com.ziprun.service.agent.AgentService;
import com.ziprun.service.order.OrderService;
import com.ziprun.service.suggestion.SuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replan Event Handler: the agentic loop.
 *
 *   OBSERVE    an agent's availability changes, delivered after the status change commits:
 *              AgentOfflineEvent (can't deliver anything), AgentBusyEvent (no new orders),
 *              AgentAvailableEvent (more capacity: re-balance all stranded orders)
 *   REASON     OFFLINE: which orders does this agent still own? (ASSIGNED / REASSIGNED / PENDING)
 *              both:    which open suggestions recommend this agent? (now stale: EXPIRED)
 *   ACT        flag owned orders REASSIGNMENT_PENDING, route every affected order with
 *              recovery context, queue an AGENT_OFFLINE suggestion
 *   CHECKPOINT nothing is reassigned here; ops accepts/rejects via PATCH /suggestions/{id}
 *
 * Threading and transactions:
 * - @TransactionalEventListener(AFTER_COMMIT): never runs against uncommitted state,
 *   and doesn't run at all if the status update rolled back.
 * - @Async: runs on the "ziprun-" task pool; PATCH /agents/{id}/status has already returned.
 * - No transaction around the whole loop: each step is its own short transaction
 *   inside the services, so an LLM call never holds a DB connection open and one
 *   bad order can't roll back the others.
 *
 * Idempotency (ADR-8): orders that already have a PENDING AGENT_OFFLINE suggestion
 * are skipped, both before routing (cheap) and again under a row lock at insert
 * time (safe against two concurrent runs). Orders left PENDING without a suggestion
 * by an earlier failed run are picked up again on the next trigger.
 */
@Service
public class ReplanEventHandler {
    private static final Logger log = LoggerFactory.getLogger(ReplanEventHandler.class);

    private final OrderService orderService;
    private final SuggestionService suggestionService;
    private final RoutingService routingService;
    private final AgentService agentService;

    public ReplanEventHandler(OrderService orderService, SuggestionService suggestionService,
                              RoutingService routingService, AgentService agentService) {
        this.orderService = orderService;
        this.suggestionService = suggestionService;
        this.routingService = routingService;
        this.agentService = agentService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentOffline(AgentOfflineEvent event) {
        replan(event.getAgentId(), event.getAgentName(), "OFFLINE", true);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentBusy(AgentBusyEvent event) {
        replan(event.getAgentId(), event.getAgentName(), "BUSY", false);
    }

    /**
     * @param ownOrdersStranded true for OFFLINE (their orders need new agents),
     *                          false for BUSY (they keep delivering their own orders)
     */
    private void replan(String agentId, String agentName, String newStatus, boolean ownOrdersStranded) {
        try {
            // REASON (1/2): which orders does this agent own and can no longer deliver?
            List<Order> owned = ownOrdersStranded ? orderService.findActiveOrdersForAgent(agentId) : List.of();

            // REASON (2/2): which open suggestions point AT this agent? They are now stale:
            // withdraw them, and re-plan any order that was waiting on one.
            List<String> withdrawnFor = List.of();
            try {
                withdrawnFor = suggestionService.expirePendingRecommending(agentId);
            } catch (Exception e) {
                // Don't let cleanup of stale suggestions block re-planning the agent's own orders
                log.error("Could not withdraw suggestions pointing at {}; continuing with owned orders", agentId, e);
            }
            List<Order> affected = new ArrayList<>(owned);
            for (String orderId : withdrawnFor) {
                Order order = orderService.getById(orderId);
                boolean alreadyIncluded = affected.stream().anyMatch(o -> o.getId().equals(orderId));
                if (!alreadyIncluded && order.getStatus() == OrderStatus.REASSIGNMENT_PENDING) {
                    affected.add(order);
                }
            }

            log.info("AGENTIC LOOP: agent {} ({}) is now {}; {} owned order(s) stranded, {} suggestion(s) pointing at "
                + "them withdrawn; {} order(s) to re-plan", agentId, agentName, newStatus, owned.size(),
                withdrawnFor.size(), affected.size());
            if (affected.isEmpty()) {
                return;
            }

            // ACT (1/2): flag the owned orders first so the UI shows them as pending immediately
            for (Order order : owned) {
                orderService.markReassignmentPending(order.getId());
            }

            // ACT (2/2): route every affected order
            replanOrders(affected, agentId + " " + newStatus);
        } catch (Exception e) {
            // Never let an async failure vanish silently
            log.error("AGENTIC LOOP aborted for agent {}: {}", agentId, e.getMessage(), e);
        }
    }

    /**
     * An agent became AVAILABLE: capacity grew, so re-balance. Every stranded order's
     * open suggestion is withdrawn and the order re-planned against the new roster;
     * pending-load balancing then spreads them across everyone available, and orders
     * that had no candidate before get one. Still only suggestions: ops decides.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentAvailable(AgentAvailableEvent event) {
        try {
            List<String> stranded = suggestionService.withdrawSuggestionsForStrandedOrders();
            log.info("AGENTIC LOOP: agent {} ({}) is now AVAILABLE; re-balancing {} stranded order(s)",
                event.getAgentId(), event.getAgentName(), stranded.size());
            if (!stranded.isEmpty()) {
                replanOrders(stranded.stream().map(orderService::getById).toList(), event.getAgentId() + " AVAILABLE");
            }
        } catch (Exception e) {
            log.error("Re-balance aborted after {} became AVAILABLE: {}", event.getAgentId(), e.getMessage(), e);
        }
    }

    /**
     * Routes each order and queues a suggestion. The recovery context names the agent who
     * stranded *that* order (its assigned agent), so the AI's incident report is accurate
     * whatever triggered the re-plan.
     */
    private void replanOrders(List<Order> affected, String trigger) {
        Map<String, List<Order>> byStrandingAgent = new LinkedHashMap<>();
        affected.forEach(o -> byStrandingAgent.computeIfAbsent(o.getAssignedAgentId(), k -> new ArrayList<>()).add(o));

        int created = 0, skipped = 0, noCandidate = 0, failed = 0;
        for (Map.Entry<String, List<Order>> group : byStrandingAgent.entrySet()) {
            RoutingContext context = RoutingContext.agentOffline(group.getKey(), nameOf(group.getKey()), group.getValue());
            for (Order order : group.getValue()) {
                try {
                    switch (replanOrder(order, context)) {
                        case CREATED -> created++;
                        case SKIPPED -> skipped++;
                        case NO_CANDIDATE -> noCandidate++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("Re-plan failed for order {} (trigger: {}); it stays REASSIGNMENT_PENDING "
                        + "and will be retried on the next trigger", order.getId(), trigger, e);
                }
            }
        }

        log.info("AGENTIC LOOP done ({}): {} suggestion(s) queued, {} skipped (already pending), "
            + "{} with no available agent, {} failed", trigger, created, skipped, noCandidate, failed);
        if (noCandidate > 0) {
            log.warn("{} order(s) have NO available agent to suggest; ops must add capacity "
                + "or reassign manually", noCandidate);
        }
    }

    private String nameOf(String agentId) {
        return agentService.findById(agentId).map(Agent::getName).orElse(agentId);
    }

    private enum Outcome { CREATED, SKIPPED, NO_CANDIDATE }

    /** The roster can change mid-routing (slow LLM); one re-route covers that. */
    private static final int MAX_ROUTING_ATTEMPTS = 2;

    private Outcome replanOrder(Order order, RoutingContext context) {
        if (suggestionService.hasPendingOfflineSuggestion(order.getId())) {
            return Outcome.SKIPPED;
        }

        for (int attempt = 1; ; attempt++) {
            // Outside any transaction: may call the LLM (bounded by llm.timeout-ms per provider)
            Optional<RoutingResult> result = routingService.route(order, context);
            if (result.isEmpty()) {
                return Outcome.NO_CANDIDATE;
            }
            try {
                return suggestionService.createReplanSuggestionIfAbsent(order.getId(), result.get()).isPresent()
                    ? Outcome.CREATED
                    : Outcome.SKIPPED;
            } catch (StaleRecommendationException e) {
                if (attempt >= MAX_ROUTING_ATTEMPTS) {
                    throw e;
                }
                log.info("Order {}: {}. Re-routing.", order.getId(), e.getMessage());
            }
        }
    }
}
