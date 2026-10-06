package com.ziprun.service.suggestion;

import com.ziprun.domain.Activity;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.exception.InvalidStateException;
import com.ziprun.exception.NotFoundException;
import com.ziprun.exception.StaleRecommendationException;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.OrderRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.routing.RoutingResult;
import com.ziprun.service.activity.ActivityService;
import com.ziprun.service.live.ChangeTracker;
import com.ziprun.service.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Suggestion Service Implementation: All suggestion business logic lives here.
 *
 * Responsibilities:
 * - Persist suggestions produced by the routing engine (both trigger paths)
 * - Idempotency for agentic re-plans (ADR-8)
 * - The human checkpoint: PENDING -> ACCEPTED (reassign order) | REJECTED
 *
 * Controller → SuggestionService → SuggestionRepository (clean separation)
 */
@Service
@Transactional
public class SuggestionServiceImpl implements SuggestionService {
    private static final Logger log = LoggerFactory.getLogger(SuggestionServiceImpl.class);
    private static final int MAX_REASONING_LENGTH = 1000;

    private final ReassignmentSuggestionRepository suggestionRepository;
    private final OrderRepository orderRepository;
    private final AgentRepository agentRepository;
    private final OrderService orderService;
    private final ActivityService activity;
    private final ChangeTracker changes;

    public SuggestionServiceImpl(
            ReassignmentSuggestionRepository suggestionRepository,
            OrderRepository orderRepository,
            AgentRepository agentRepository,
            OrderService orderService,
            ActivityService activity,
            ChangeTracker changes
    ) {
        this.suggestionRepository = suggestionRepository;
        this.orderRepository = orderRepository;
        this.agentRepository = agentRepository;
        this.orderService = orderService;
        this.activity = activity;
        this.changes = changes;
    }

    @Override
    public ReassignmentSuggestion createSuggestion(String orderId, RoutingResult result, TriggerReason triggerReason) {
        // Routing read the roster before (possibly slow) LLM calls; re-check right before saving
        boolean stillAvailable = agentRepository.findById(result.getRecommendedAgentId())
            .map(agent -> agent.getStatus() == AgentStatus.AVAILABLE)
            .orElse(false);
        if (!stillAvailable) {
            throw new StaleRecommendationException(result.getRecommendedAgentId());
        }

        ReassignmentSuggestion suggestion = new ReassignmentSuggestion();
        suggestion.setId("SUGG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        suggestion.setOrderId(orderId);
        suggestion.setRecommendedAgentId(result.getRecommendedAgentId());
        suggestion.setConfidence(result.getConfidence());
        suggestion.setReasoning(truncate(result.getReasoning()));
        suggestion.setSource(result.getSource());
        suggestion.setRoutingMillis(result.getRoutingMillis());
        suggestion.setStatus(SuggestionStatus.PENDING);
        suggestion.setTriggerReason(triggerReason);
        suggestion.setCreatedAt(LocalDateTime.now());

        ReassignmentSuggestion saved = suggestionRepository.save(suggestion);
        log.info("Suggestion created: id={}, order={}, agent={}, confidence={}, trigger={}, source={}",
            saved.getId(), orderId, saved.getRecommendedAgentId(), saved.getConfidence(),
            triggerReason, saved.getSource());
        activity.record(Activity.Type.SUGGESTION_CREATED,
            triggerReason == TriggerReason.AGENT_OFFLINE ? Activity.Actor.SYSTEM : Activity.Actor.OPS,
            String.format("Suggested %s for %s (%s, %d%% confidence)", name(saved.getRecommendedAgentId()), orderId,
                saved.getSource(), Math.round(saved.getConfidence() * 100)),
            orderId, saved.getRecommendedAgentId(), saved.getId());
        return saved;
    }

    @Override
    public Optional<ReassignmentSuggestion> createReplanSuggestionIfAbsent(String orderId, RoutingResult result) {
        // Lock the order row: a concurrent re-plan of the same order blocks here until we commit,
        // then sees our suggestion in the check below.
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));

        // Ops may have resolved it (keep / reassign / accept) while routing was running
        if (order.getStatus() != OrderStatus.REASSIGNMENT_PENDING) {
            log.info("Order {} is {} now; dropping late re-plan suggestion", orderId, order.getStatus());
            return Optional.empty();
        }
        if (hasPendingOfflineSuggestion(orderId)) {
            log.info("Idempotency: order {} already has a PENDING AGENT_OFFLINE suggestion; skipping", orderId);
            return Optional.empty();
        }
        return Optional.of(createSuggestion(orderId, result, TriggerReason.AGENT_OFFLINE));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReassignmentSuggestion> findById(String suggestionId) {
        return suggestionRepository.findById(suggestionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReassignmentSuggestion> findAll() {
        return suggestionRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReassignmentSuggestion> findByStatus(SuggestionStatus status) {
        return suggestionRepository.findByStatus(status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReassignmentSuggestion> findByOrderId(String orderId) {
        return suggestionRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<ReassignmentSuggestion> createSlaSuggestionIfAbsent(String orderId, RoutingResult result) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
        if (order.getStatus() != OrderStatus.ASSIGNED && order.getStatus() != OrderStatus.REASSIGNED) {
            log.info("Order {} is {} now; dropping SLA suggestion", orderId, order.getStatus());
            return Optional.empty();
        }
        if (!suggestionRepository.findByOrderIdAndStatus(orderId, SuggestionStatus.PENDING).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(createSuggestion(orderId, result, TriggerReason.SLA_RISK));
    }

    @Override
    public ReassignmentSuggestion updateStatus(String suggestionId, SuggestionStatus newStatus) {
        log.debug("Deciding suggestion: id={}, newStatus={}", suggestionId, newStatus);

        if (newStatus != SuggestionStatus.ACCEPTED && newStatus != SuggestionStatus.REJECTED) {
            throw new IllegalArgumentException("Status must be ACCEPTED or REJECTED, got " + newStatus);
        }

        ReassignmentSuggestion suggestion = suggestionRepository.findById(suggestionId)
            .orElseThrow(() -> new NotFoundException("Suggestion", suggestionId));

        if (suggestion.getStatus() != SuggestionStatus.PENDING) {
            throw new InvalidStateException(String.format(
                "Suggestion %s is already %s; only PENDING suggestions can be decided",
                suggestionId, suggestion.getStatus()));
        }

        LocalDateTime now = LocalDateTime.now();
        if (newStatus == SuggestionStatus.ACCEPTED) {
            // A suggestion is a recommendation to give an AVAILABLE agent one more order;
            // if they've since gone BUSY or OFFLINE, ops should get a fresh suggestion instead
            agentRepository.findById(suggestion.getRecommendedAgentId())
                .filter(agent -> agent.getStatus() != AgentStatus.AVAILABLE)
                .ifPresent(agent -> {
                    throw new InvalidStateException(String.format(
                        "%s is %s now and isn't taking new orders; request a new suggestion",
                        agent.getName(), agent.getStatus()));
                });
            // Throws (and rolls back the whole decision) on any other conflict
            orderService.reassignToAgent(suggestion.getOrderId(), suggestion.getRecommendedAgentId());

            // Other open suggestions for this order are now moot
            suggestionRepository.findByOrderIdAndStatus(suggestion.getOrderId(), SuggestionStatus.PENDING).stream()
                .filter(other -> !other.getId().equals(suggestionId))
                .forEach(other -> {
                    other.setStatus(SuggestionStatus.REJECTED);
                    other.setDecidedAt(now);
                    log.info("Suggestion {} superseded by accepted {}", other.getId(), suggestionId);
                });
        }

        suggestion.setStatus(newStatus);
        suggestion.setDecidedAt(now);
        log.info("Suggestion {}: order={}, agent={}", newStatus, suggestion.getOrderId(), suggestion.getRecommendedAgentId());
        boolean accepted = newStatus == SuggestionStatus.ACCEPTED;
        activity.record(accepted ? Activity.Type.SUGGESTION_ACCEPTED : Activity.Type.SUGGESTION_REJECTED, Activity.Actor.OPS,
            String.format("%s suggestion for %s → %s (%s)", accepted ? "Accepted" : "Rejected", suggestion.getOrderId(),
                name(suggestion.getRecommendedAgentId()), suggestion.getSource()),
            suggestion.getOrderId(), suggestion.getRecommendedAgentId(), suggestionId);
        return suggestion;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPendingOfflineSuggestion(String orderId) {
        return suggestionRepository.existsByOrderIdAndStatusAndTriggerReason(
            orderId, SuggestionStatus.PENDING, TriggerReason.AGENT_OFFLINE);
    }

    @Override
    public List<String> expirePendingRecommending(String agentId) {
        List<ReassignmentSuggestion> stale = suggestionRepository.findByRecommendedAgentIdAndStatus(agentId, SuggestionStatus.PENDING);
        int expired = suggestionRepository.expirePendingRecommending(agentId, LocalDateTime.now());
        if (expired > 0) {
            changes.afterCommit("suggestions");
            log.info("{} suggestion(s) EXPIRED: recommended agent {} is no longer AVAILABLE", expired, agentId);
            activity.record(Activity.Type.SUGGESTIONS_WITHDRAWN, Activity.Actor.SYSTEM,
                String.format("%d suggestion(s) withdrawn: %s is no longer available", expired, name(agentId)),
                null, agentId, null);
        }
        return stale.stream().map(ReassignmentSuggestion::getOrderId).distinct().toList();
    }

    @Override
    public Order keepWithCurrentAgent(String orderId) {
        Order order = orderService.getById(orderId);
        if (order.getStatus() != OrderStatus.REASSIGNMENT_PENDING) {
            throw new InvalidStateException("Order " + orderId + " is " + order.getStatus() + ", not REASSIGNMENT_PENDING");
        }
        // updateStatus refuses if the agent is still OFFLINE
        Order kept = orderService.updateStatus(orderId, OrderStatus.ASSIGNED);
        expirePendingFor(orderId);
        log.info("Order {} kept with original agent {} (back online); pending suggestions expired",
            orderId, kept.getAssignedAgentId());
        activity.record(Activity.Type.ORDER_KEPT, Activity.Actor.OPS,
            String.format("%s kept with %s (available again)", orderId, name(kept.getAssignedAgentId())),
            orderId, kept.getAssignedAgentId(), null);
        return kept;
    }

    @Override
    public List<String> withdrawSuggestionsForStrandedOrders() {
        List<String> stranded = orderRepository.findByStatus(OrderStatus.REASSIGNMENT_PENDING).stream()
            .map(Order::getId)
            .toList();
        int withdrawn = stranded.stream().mapToInt(this::expirePendingFor).sum();
        if (withdrawn > 0) {
            activity.record(Activity.Type.SUGGESTIONS_WITHDRAWN, Activity.Actor.SYSTEM,
                String.format("Re-balancing %d waiting order(s): %d open suggestion(s) withdrawn", stranded.size(), withdrawn));
        }
        return stranded;
    }

    @Override
    public Order reassignManually(String orderId, String newAgentId) {
        String fromAgent = orderService.getById(orderId).getAssignedAgentId();
        Order moved = orderService.reassignToAgent(orderId, newAgentId);
        expirePendingFor(orderId);
        log.info("Order {} manually reassigned to {}; open suggestions expired", orderId, newAgentId);
        activity.record(Activity.Type.ORDER_REASSIGNED, Activity.Actor.OPS,
            String.format("%s reassigned by hand: %s → %s", orderId, name(fromAgent), name(newAgentId)),
            orderId, newAgentId, null);
        return moved;
    }

    /** @return how many suggestions were expired */
    private int expirePendingFor(String orderId) {
        int expired = suggestionRepository.expirePendingForOrder(orderId, LocalDateTime.now());
        if (expired > 0) {
            changes.afterCommit("suggestions");
        }
        return expired;
    }

    private String name(String agentId) {
        return agentRepository.findById(agentId).map(a -> a.getName()).orElse(agentId);
    }

    private static String truncate(String reasoning) {
        if (reasoning == null) {
            return "";
        }
        return reasoning.length() <= MAX_REASONING_LENGTH ? reasoning : reasoning.substring(0, MAX_REASONING_LENGTH - 3) + "...";
    }
}
