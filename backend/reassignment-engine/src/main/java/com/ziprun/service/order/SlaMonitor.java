package com.ziprun.service.order;

import com.ziprun.domain.Activity;
import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.exception.StaleRecommendationException;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.OrderRepository;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingService;
import com.ziprun.service.activity.ActivityService;
import com.ziprun.service.suggestion.SuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * SLA monitor: re-plans proactively when an order is about to miss its deadline,
 * not only when its agent goes offline.
 *
 * Every orders.sla.check-interval-ms it looks for ASSIGNED / REASSIGNED orders whose
 * slaDeadline is within orders.sla.at-risk-minutes (or already past) and that haven't
 * been flagged yet. For each one, once:
 *   1. claim it (slaAlertedAt, under a row lock) and log "at risk" to the activity feed
 *   2. route it with an SLA_RISK context (same strategies, capacity and zone rules)
 *   3. if the best agent could start sooner than the current one (fewer orders ahead),
 *      queue an SLA_RISK suggestion; otherwise note that it stays put
 * Ops decides as usual: accept moves the order, reject keeps it with its agent.
 * Orders waiting for a new agent (REASSIGNMENT_PENDING) are already in the queue,
 * which sorts them by deadline.
 */
@Component
public class SlaMonitor {
    private static final Logger log = LoggerFactory.getLogger(SlaMonitor.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final OrderRepository orders;
    private final AgentRepository agents;
    private final OrderService orderService;
    private final SuggestionService suggestionService;
    private final RoutingService routingService;
    private final ActivityService activity;
    private final Duration atRisk;

    public SlaMonitor(OrderRepository orders, AgentRepository agents, OrderService orderService,
                      SuggestionService suggestionService, RoutingService routingService, ActivityService activity,
                      @Value("${orders.sla.at-risk-minutes:30}") long atRiskMinutes) {
        this.orders = orders;
        this.agents = agents;
        this.orderService = orderService;
        this.suggestionService = suggestionService;
        this.routingService = routingService;
        this.activity = activity;
        this.atRisk = Duration.ofMinutes(atRiskMinutes);
    }

    @Scheduled(fixedDelayString = "${orders.sla.check-interval-ms:30000}",
               initialDelayString = "${orders.sla.check-interval-ms:30000}")
    public void checkDeadlines() {
        LocalDateTime cutoff = LocalDateTime.now().plus(atRisk);
        List<Order> atRiskOrders = orders.findUnflaggedDueBefore(
            EnumSet.of(OrderStatus.ASSIGNED, OrderStatus.REASSIGNED), cutoff);
        for (Order order : atRiskOrders) {
            try {
                handle(order.getId());
            } catch (Exception e) {
                log.error("SLA check failed for order {}", order.getId(), e);
            }
        }
    }

    void handle(String orderId) {
        Optional<Order> claimed = orderService.claimSlaAlert(orderId);
        if (claimed.isEmpty()) {
            return;
        }
        Order order = claimed.get();
        Agent current = agents.findById(order.getAssignedAgentId()).orElse(null);
        long minutesLeft = Duration.between(LocalDateTime.now(), order.getSlaDeadline()).toMinutes();
        String when = minutesLeft >= 0 ? "due " + order.getSlaDeadline().format(TIME) + " (" + minutesLeft + " min left)"
                                       : minutesLeft * -1 + " min late";
        String currentName = current == null ? order.getAssignedAgentId() : current.getName();
        int currentLoad = current == null || current.getActiveOrderCount() == null ? 0 : current.getActiveOrderCount();

        Optional<RoutingResult> best = Optional.empty();
        try {
            best = routingService.route(order, RoutingContext.slaRisk());
        } catch (RuntimeException e) {
            log.warn("SLA routing failed for {}: {}", orderId, e.getMessage());
        }

        // Faster = fewer orders ahead of this one than with the current agent (who has it plus currentLoad-1 others)
        Optional<RoutingResult> faster = best.filter(r -> routingService.effectiveLoadOf(r.getRecommendedAgentId()) < currentLoad - 1);
        String outcome;
        if (faster.isPresent()) {
            try {
                suggestionService.createSlaSuggestionIfAbsent(orderId, faster.get());
                outcome = "suggested moving it to " + routingService.agentName(faster.get().getRecommendedAgentId());
            } catch (StaleRecommendationException e) {
                outcome = "the faster agent just became unavailable; it stays with " + currentName;
            }
        } else {
            outcome = "no agent could start it sooner; it stays with " + currentName;
        }
        activity.record(Activity.Type.SLA_AT_RISK, Activity.Actor.SYSTEM,
            String.format("%s at risk of missing its deadline: %s with %s (%d active order%s); %s",
                orderId, when, currentName, currentLoad, currentLoad == 1 ? "" : "s", outcome),
            orderId, order.getAssignedAgentId(), null);
        log.info("SLA: order {} {} with {}; {}", orderId, when, currentName, outcome);
    }
}
