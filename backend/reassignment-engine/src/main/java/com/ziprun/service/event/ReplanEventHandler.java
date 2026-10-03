package com.ziprun.service.event;

import com.ziprun.domain.Order;
import com.ziprun.domain.event.AgentOfflineEvent;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingService;
import com.ziprun.service.order.OrderService;
import com.ziprun.service.suggestion.SuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.List;
import java.util.Optional;

/**
 * Replan Event Handler: the agentic loop.
 *
 *   OBSERVE    AgentOfflineEvent, delivered after the status change commits
 *   REASON     which orders does this agent still own? (ASSIGNED / REASSIGNED / already PENDING)
 *   ACT        flag them REASSIGNMENT_PENDING, route each with recovery context,
 *              queue an AGENT_OFFLINE suggestion
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

    public ReplanEventHandler(OrderService orderService, SuggestionService suggestionService, RoutingService routingService) {
        this.orderService = orderService;
        this.suggestionService = suggestionService;
        this.routingService = routingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentOffline(AgentOfflineEvent event) {
        String agentId = event.getAgentId();
        String agentName = event.getAgentName();

        try {
            // REASON: which orders are stranded?
            List<Order> stranded = orderService.findActiveOrdersForAgent(agentId);
            log.info("AGENTIC LOOP: agent {} ({}) went OFFLINE; {} stranded order(s)", agentId, agentName, stranded.size());
            if (stranded.isEmpty()) {
                return;
            }

            // ACT (1/2): flag them all first so the UI shows them as pending immediately
            for (Order order : stranded) {
                orderService.markReassignmentPending(order.getId());
            }

            // ACT (2/2): route each with the same recovery context (whole batch is visible to the AI)
            RoutingContext context = RoutingContext.agentOffline(agentId, agentName, stranded);
            int created = 0, skipped = 0, noCandidate = 0, failed = 0;
            for (Order order : stranded) {
                try {
                    switch (replanOrder(order, context)) {
                        case CREATED -> created++;
                        case SKIPPED -> skipped++;
                        case NO_CANDIDATE -> noCandidate++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("Re-plan failed for order {} (agent {} offline); it stays REASSIGNMENT_PENDING "
                        + "and will be retried on the next trigger", order.getId(), agentId, e);
                }
            }

            log.info("AGENTIC LOOP done for {}: {} suggestion(s) queued, {} skipped (already pending), "
                + "{} with no available agent, {} failed", agentId, created, skipped, noCandidate, failed);
            if (noCandidate > 0) {
                log.warn("{} order(s) stranded by {} have NO available agent to suggest; ops must add capacity "
                    + "or reassign manually", noCandidate, agentId);
            }
        } catch (Exception e) {
            // Never let an async failure vanish silently
            log.error("AGENTIC LOOP aborted for agent {}: {}", agentId, e.getMessage(), e);
        }
    }

    private enum Outcome { CREATED, SKIPPED, NO_CANDIDATE }

    private Outcome replanOrder(Order order, RoutingContext context) {
        if (suggestionService.hasPendingOfflineSuggestion(order.getId())) {
            return Outcome.SKIPPED;
        }

        // Outside any transaction: may call the LLM (bounded by llm.timeout-ms per provider)
        Optional<RoutingResult> result = routingService.route(order, context);
        if (result.isEmpty()) {
            return Outcome.NO_CANDIDATE;
        }

        return suggestionService.createReplanSuggestionIfAbsent(order.getId(), result.get()).isPresent()
            ? Outcome.CREATED
            : Outcome.SKIPPED;
    }
}
