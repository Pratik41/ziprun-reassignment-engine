package com.ziprun.service.suggestion;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.exception.InvalidStateException;
import com.ziprun.exception.NotFoundException;
import com.ziprun.repository.OrderRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.routing.RoutingResult;
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
    private final OrderService orderService;

    public SuggestionServiceImpl(
            ReassignmentSuggestionRepository suggestionRepository,
            OrderRepository orderRepository,
            OrderService orderService
    ) {
        this.suggestionRepository = suggestionRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    @Override
    public ReassignmentSuggestion createSuggestion(String orderId, RoutingResult result, TriggerReason triggerReason) {
        ReassignmentSuggestion suggestion = new ReassignmentSuggestion();
        suggestion.setId("SUGG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        suggestion.setOrderId(orderId);
        suggestion.setRecommendedAgentId(result.getRecommendedAgentId());
        suggestion.setConfidence(result.getConfidence());
        suggestion.setReasoning(truncate(result.getReasoning()));
        suggestion.setSource(result.getSource());
        suggestion.setStatus(SuggestionStatus.PENDING);
        suggestion.setTriggerReason(triggerReason);
        suggestion.setCreatedAt(LocalDateTime.now());

        ReassignmentSuggestion saved = suggestionRepository.save(suggestion);
        log.info("Suggestion created: id={}, order={}, agent={}, confidence={}, trigger={}, source={}",
            saved.getId(), orderId, saved.getRecommendedAgentId(), saved.getConfidence(),
            triggerReason, saved.getSource());
        return saved;
    }

    @Override
    public Optional<ReassignmentSuggestion> createReplanSuggestionIfAbsent(String orderId, RoutingResult result) {
        // Lock the order row: a concurrent re-plan of the same order blocks here until we commit,
        // then sees our suggestion in the check below.
        orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));

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
            // Throws (and rolls back the whole decision) if e.g. the agent went offline meanwhile
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
        return suggestion;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPendingOfflineSuggestion(String orderId) {
        return suggestionRepository.existsByOrderIdAndStatusAndTriggerReason(
            orderId, SuggestionStatus.PENDING, TriggerReason.AGENT_OFFLINE);
    }

    private static String truncate(String reasoning) {
        if (reasoning == null) {
            return "";
        }
        return reasoning.length() <= MAX_REASONING_LENGTH ? reasoning : reasoning.substring(0, MAX_REASONING_LENGTH - 3) + "...";
    }
}
