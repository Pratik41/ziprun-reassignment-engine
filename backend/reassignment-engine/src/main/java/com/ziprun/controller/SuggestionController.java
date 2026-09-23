package com.ziprun.controller;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST API for reassignment suggestions.
 *
 * Endpoints:
 * GET /suggestions - List suggestions (filterable by status)
 * GET /suggestions/{id} - Get single suggestion
 * PATCH /suggestions/{id} - Accept or reject suggestion
 *
 * Operations:
 * - Accept: Ops approves suggested agent reassignment
 * - Reject: Ops rejects and keeps order with current agent
 */
@RestController
@RequestMapping("/suggestions")
public class SuggestionController {

    private final ReassignmentSuggestionRepository suggestionRepository;

    public SuggestionController(ReassignmentSuggestionRepository suggestionRepository) {
        this.suggestionRepository = suggestionRepository;
    }

    @GetMapping
    public ResponseEntity<List<ReassignmentSuggestion>> listSuggestions(
            @RequestParam(name = "status", required = false) String statusStr
    ) {
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                SuggestionStatus status = SuggestionStatus.valueOf(statusStr.toUpperCase());
                return ResponseEntity.ok(suggestionRepository.findByStatus(status));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.ok(suggestionRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReassignmentSuggestion> getSuggestion(@PathVariable String id) {
        return suggestionRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ReassignmentSuggestion> updateSuggestion(
            @PathVariable String id,
            @RequestBody UpdateSuggestionRequest request
    ) {
        return suggestionRepository.findById(id)
                .map(suggestion -> {
                    try {
                        SuggestionStatus newStatus = SuggestionStatus.valueOf(request.getStatus().toUpperCase());
                        suggestion.setStatus(newStatus);
                        suggestion.setDecidedAt(LocalDateTime.now());

                        // TODO: When ACCEPTED, update order status and reassigned agent
                        // - Get order by suggestion.orderId
                        // - Update order.status = REASSIGNED
                        // - Update order.assignedAgentId = suggestion.recommendedAgentId
                        // - Save order

                        ReassignmentSuggestion saved = suggestionRepository.save(suggestion);
                        return ResponseEntity.ok(saved);
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().<ReassignmentSuggestion>build();
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // DTO Class
    public static class UpdateSuggestionRequest {
        private String status;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
