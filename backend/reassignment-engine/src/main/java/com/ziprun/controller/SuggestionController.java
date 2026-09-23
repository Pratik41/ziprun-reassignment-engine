package com.ziprun.controller;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.service.order.OrderService;
import com.ziprun.service.suggestion.SuggestionService;
import com.ziprun.domain.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Suggestion Controller: REST API for reassignment suggestions.
 *
 * RESPONSIBILITY: Handle HTTP concerns only
 * - Parse request body
 * - Validate input
 * - Call business logic (SuggestionService, OrderService)
 * - Format response
 * - Return HTTP status codes
 *
 * DOES NOT: Query database directly, coordinate complex state changes
 *
 * Endpoints:
 * GET    /suggestions              - List suggestions (filterable by status)
 * GET    /suggestions/{id}         - Get single suggestion
 * PATCH  /suggestions/{id}         - Accept or reject suggestion
 *
 * Operations:
 * - Accept (status=ACCEPTED): Ops approves reassignment → update order + assign new agent
 * - Reject (status=REJECTED): Ops rejects → keep order with current agent
 */
@RestController
@RequestMapping("/suggestions")
public class SuggestionController {
    private static final Logger log = LoggerFactory.getLogger(SuggestionController.class);

    private final SuggestionService suggestionService;
    private final OrderService orderService;

    public SuggestionController(SuggestionService suggestionService, OrderService orderService) {
        this.suggestionService = suggestionService;
        this.orderService = orderService;
    }

    /**
     * GET /suggestions - List all suggestions, optionally filtered by status.
     *
     * Query params: ?status=PENDING | ACCEPTED | REJECTED
     * Response: 200 OK + list of suggestions
     */
    @GetMapping
    public ResponseEntity<?> listSuggestions(@RequestParam(name = "status", required = false) String statusStr) {
        log.debug("GET /suggestions: status={}", statusStr);

        try {
            List<ReassignmentSuggestion> suggestions;

            if (statusStr != null && !statusStr.isBlank()) {
                SuggestionStatus status = SuggestionStatus.valueOf(statusStr.toUpperCase());
                suggestions = suggestionService.findByStatus(status);
            } else {
                suggestions = suggestionService.findAll();
            }

            return ResponseEntity.ok(suggestions);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid suggestion status: {}", statusStr);
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid status: " + statusStr));
        }
    }

    /**
     * GET /suggestions/{id} - Get single suggestion by ID.
     *
     * Response: 200 OK + suggestion details, or 404 Not Found
     */
    @GetMapping("/{id}")
    public ResponseEntity<ReassignmentSuggestion> getSuggestion(@PathVariable String id) {
        log.debug("GET /suggestions/{}: id={}", id, id);

        return suggestionService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.warn("Suggestion not found: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * PATCH /suggestions/{id} - Accept or reject suggestion.
     *
     * When ACCEPTED:
     * 1. Update suggestion status to ACCEPTED
     * 2. Update order: status → REASSIGNED, assignedAgentId → recommended agent
     *
     * When REJECTED:
     * 1. Update suggestion status to REJECTED
     * 2. Order status stays as-is (not reassigned)
     *
     * Request: { "status": "ACCEPTED" | "REJECTED" }
     * Response: 200 OK + updated suggestion, or 400/404
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateSuggestion(
            @PathVariable String id,
            @RequestBody UpdateSuggestionRequest request
    ) {
        log.debug("PATCH /suggestions/{}: newStatus={}", id, request.getStatus());

        if (request.getStatus() == null || request.getStatus().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Status is required"));
        }

        try {
            SuggestionStatus newStatus = SuggestionStatus.valueOf(request.getStatus().toUpperCase());
            ReassignmentSuggestion updated = suggestionService.updateStatus(id, newStatus);

            // If ACCEPTED: update the order and reassign
            if (newStatus == SuggestionStatus.ACCEPTED) {
                handleAcceptedSuggestion(updated);
            }

            log.info("Suggestion updated: id={}, status={}", id, newStatus);
            return ResponseEntity.ok(updated);

        } catch (IllegalArgumentException e) {
            log.warn("Failed to update suggestion: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error updating suggestion", e);
            return ResponseEntity.status(500)
                    .body(new ErrorResponse("Failed to update suggestion"));
        }
    }

    /**
     * Internal: Handle accepted suggestion.
     * Updates the associated order to REASSIGNED status and assigns new agent.
     */
    private void handleAcceptedSuggestion(ReassignmentSuggestion suggestion) {
        try {
            // Update order: mark as REASSIGNED with new agent
            orderService.updateStatus(suggestion.getOrderId(), OrderStatus.REASSIGNED);

            log.info(
                "Order reassigned: orderId={}, newAgentId={}",
                suggestion.getOrderId(),
                suggestion.getRecommendedAgentId()
            );

        } catch (Exception e) {
            log.error(
                "Failed to reassign order {} to agent {}",
                suggestion.getOrderId(),
                suggestion.getRecommendedAgentId(),
                e
            );
            // Don't throw - suggestion is already accepted, but log for ops team
        }
    }

    // ============ DTOs ============

    public static class UpdateSuggestionRequest {
        private String status;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class ErrorResponse {
        private String message;

        public ErrorResponse(String message) { this.message = message; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
