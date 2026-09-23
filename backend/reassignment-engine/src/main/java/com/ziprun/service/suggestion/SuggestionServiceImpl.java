package com.ziprun.service.suggestion;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Suggestion Service Implementation: All suggestion business logic lives here.
 *
 * Responsibilities:
 * - Persist suggestions created by routing engine
 * - Query suggestions for UI (pending reassignments)
 * - Update suggestions when ops approves/rejects
 * - Idempotency checks (prevent duplicate re-plans)
 * - Validate state transitions (PENDING → ACCEPTED/REJECTED)
 *
 * Controller → SuggestionService → SuggestionRepository (clean separation)
 */
@Service
@Transactional
public class SuggestionServiceImpl implements SuggestionService {
    private static final Logger log = LoggerFactory.getLogger(SuggestionServiceImpl.class);

    private final ReassignmentSuggestionRepository suggestionRepository;

    public SuggestionServiceImpl(ReassignmentSuggestionRepository suggestionRepository) {
        this.suggestionRepository = suggestionRepository;
    }

    @Override
    public ReassignmentSuggestion createSuggestion(ReassignmentSuggestion suggestion) {
        log.debug(
            "Creating suggestion: orderId={}, agent={}, reason={}",
            suggestion.getOrderId(),
            suggestion.getRecommendedAgentId(),
            suggestion.getTriggerReason()
        );

        // Ensure defaults are set
        if (suggestion.getStatus() == null) {
            suggestion.setStatus(SuggestionStatus.PENDING);
        }
        if (suggestion.getCreatedAt() == null) {
            suggestion.setCreatedAt(LocalDateTime.now());
        }

        ReassignmentSuggestion saved = suggestionRepository.save(suggestion);

        log.info(
            "Suggestion created: id={}, orderId={}, agent={}, trigger={}",
            saved.getId(),
            saved.getOrderId(),
            saved.getRecommendedAgentId(),
            saved.getTriggerReason()
        );

        return saved;
    }

    @Override
    public Optional<ReassignmentSuggestion> findById(String suggestionId) {
        log.debug("Fetching suggestion: {}", suggestionId);
        return suggestionRepository.findById(suggestionId);
    }

    @Override
    public List<ReassignmentSuggestion> findAll() {
        log.debug("Fetching all suggestions");
        return suggestionRepository.findAll();
    }

    @Override
    public List<ReassignmentSuggestion> findByStatus(SuggestionStatus status) {
        log.debug("Fetching suggestions by status: {}", status);
        return suggestionRepository.findByStatus(status);
    }

    @Override
    public List<ReassignmentSuggestion> findByOrderId(String orderId) {
        log.debug("Fetching suggestions for order: {}", orderId);
        return suggestionRepository.findByOrderId(orderId);
    }

    @Override
    public ReassignmentSuggestion updateStatus(String suggestionId, SuggestionStatus newStatus) {
        log.debug("Updating suggestion status: id={}, newStatus={}", suggestionId, newStatus);

        ReassignmentSuggestion suggestion = suggestionRepository.findById(suggestionId)
            .orElseThrow(() -> new IllegalArgumentException("Suggestion not found: " + suggestionId));

        // Validate transition: only PENDING can transition to ACCEPTED/REJECTED
        if (suggestion.getStatus() != SuggestionStatus.PENDING) {
            throw new IllegalArgumentException(
                String.format(
                    "Cannot update suggestion status from %s to %s. Only PENDING suggestions can be approved/rejected.",
                    suggestion.getStatus(),
                    newStatus
                )
            );
        }

        if (newStatus != SuggestionStatus.ACCEPTED && newStatus != SuggestionStatus.REJECTED) {
            throw new IllegalArgumentException(
                String.format("Can only transition to ACCEPTED or REJECTED, got %s", newStatus)
            );
        }

        // Update
        suggestion.setStatus(newStatus);
        suggestion.setDecidedAt(LocalDateTime.now());
        ReassignmentSuggestion updated = suggestionRepository.save(suggestion);

        log.info("Suggestion updated: id={}, status={}", suggestionId, newStatus);
        return updated;
    }

    @Override
    public boolean hasPendingOfflineSuggestion(String orderId) {
        log.debug("Checking for pending offline suggestion: orderId={}", orderId);

        var existing = suggestionRepository.findByOrderIdAndStatusAndTriggerReason(
            orderId,
            SuggestionStatus.PENDING,
            TriggerReason.AGENT_OFFLINE
        );

        if (existing.isPresent()) {
            log.debug(
                "Pending offline suggestion already exists for order {}: {}",
                orderId,
                existing.get().getId()
            );
        }

        return existing.isPresent();
    }
}
