package com.ziprun.controller;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.routing.RoutingService;
import com.ziprun.routing.RoutingResult;
import com.ziprun.service.order.OrderService;
import com.ziprun.service.suggestion.SuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Order Controller: REST API for order management.
 *
 * RESPONSIBILITY: Handle HTTP concerns only
 * - Parse request body
 * - Validate input (null checks)
 * - Call business logic (OrderService, RoutingService)
 * - Format response
 * - Return HTTP status codes
 *
 * DOES NOT: Query database directly, apply business rules, coordinate complex logic
 *
 * Endpoints:
 * POST   /orders              - Create order
 * GET    /orders              - List orders (filterable by status)
 * GET    /orders/{id}         - Get single order
 * PATCH  /orders/{id}/status  - Update order status
 * POST   /orders/{id}/suggest - Request reassignment suggestion
 */
@RestController
@RequestMapping("/orders")
public class OrderController {
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final SuggestionService suggestionService;
    private final RoutingService routingService;

    public OrderController(
            OrderService orderService,
            SuggestionService suggestionService,
            RoutingService routingService
    ) {
        this.orderService = orderService;
        this.suggestionService = suggestionService;
        this.routingService = routingService;
    }

    /**
     * POST /orders - Create order pre-assigned to an agent.
     * Simulates morning manual assignment workflow.
     *
     * Request: { "description": "...", "assignedAgentId": "..." }
     * Response: 201 Created + order details
     */
    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {
        log.debug("POST /orders: description={}, agent={}", request.getDescription(), request.getAssignedAgentId());

        // Validation: empty input
        if (request.getDescription() == null || request.getDescription().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Description is required"));
        }
        if (request.getAssignedAgentId() == null || request.getAssignedAgentId().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Assigned agent ID is required"));
        }

        try {
            // Delegate to service (which validates agent exists, not offline, etc.)
            Order created = orderService.createOrder(request.getDescription(), request.getAssignedAgentId());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);

        } catch (IllegalArgumentException e) {
            log.warn("Failed to create order: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error creating order", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to create order"));
        }
    }

    /**
     * GET /orders - List all orders, optionally filtered by status.
     *
     * Query params: ?status=ASSIGNED | REASSIGNMENT_PENDING | REASSIGNED | DELIVERED
     * Response: 200 OK + list of orders
     */
    @GetMapping
    public ResponseEntity<List<Order>> listOrders(@RequestParam(name = "status", required = false) String statusStr) {
        log.debug("GET /orders: status={}", statusStr);

        try {
            List<Order> orders;

            if (statusStr != null && !statusStr.isBlank()) {
                OrderStatus status = OrderStatus.valueOf(statusStr.toUpperCase());
                orders = orderService.findByStatus(status);
            } else {
                orders = orderService.findAll();
            }

            return ResponseEntity.ok(orders);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid order status: {}", statusStr);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * GET /orders/{id} - Get single order by ID.
     *
     * Response: 200 OK + order details, or 404 Not Found
     */
    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable String id) {
        log.debug("GET /orders/{}: id={}", id, id);

        return orderService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.warn("Order not found: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * PATCH /orders/{id}/status - Update order status.
     * Validates state machine transitions via service.
     *
     * Request: { "status": "REASSIGNMENT_PENDING" }
     * Response: 200 OK + updated order, or 400/404
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateOrderStatus(
            @PathVariable String id,
            @RequestBody UpdateOrderStatusRequest request
    ) {
        log.debug("PATCH /orders/{}/status: newStatus={}", id, request.getStatus());

        if (request.getStatus() == null || request.getStatus().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Status is required"));
        }

        try {
            OrderStatus newStatus = OrderStatus.valueOf(request.getStatus().toUpperCase());
            Order updated = orderService.updateStatus(id, newStatus);
            return ResponseEntity.ok(updated);

        } catch (IllegalArgumentException e) {
            log.warn("Failed to update order status: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * POST /orders/{id}/suggest - Request reassignment suggestion.
     * Calls routing engine, gets AI or rule-based recommendation, persists suggestion.
     *
     * Response: 201 Created + suggestion, or 400/404
     */
    @PostMapping("/{id}/suggest")
    public ResponseEntity<?> suggestReassignment(@PathVariable String id) {
        log.debug("POST /orders/{}/suggest: orderId={}", id, id);

        // Get order
        Order order = orderService.findById(id)
                .orElseGet(() -> {
                    log.warn("Order not found for suggestion: {}", id);
                    return null;
                });

        if (order == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            // Call routing engine (which uses AI or rule-based strategy)
            RoutingResult result = routingService.route(order);

            if (result.getRecommendedAgentId() == null) {
                log.warn("Routing returned no recommendation for order {}", id);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new ErrorResponse("No available agents for reassignment"));
            }

            // Create suggestion entity
            ReassignmentSuggestion suggestion = new ReassignmentSuggestion();
            suggestion.setId("SUGG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            suggestion.setOrderId(order.getId());
            suggestion.setRecommendedAgentId(result.getRecommendedAgentId());
            suggestion.setConfidence(result.getConfidence());
            suggestion.setReasoning(result.getReasoning());
            suggestion.setStatus(SuggestionStatus.PENDING);
            suggestion.setTriggerReason(TriggerReason.INITIAL);
            suggestion.setCreatedAt(LocalDateTime.now());

            // Persist via service
            ReassignmentSuggestion saved = suggestionService.createSuggestion(suggestion);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);

        } catch (Exception e) {
            log.error("Failed to generate suggestion for order {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to generate suggestion"));
        }
    }

    /**
     * POST /orders/{id}/reassign - Manual reassignment by ops.
     * Operator can override AI suggestion and pick any agent.
     *
     * Request: { "newAgentId": "AGT-002" }
     * Response: 200 OK + updated order, or 400/404
     */
    @PostMapping("/{id}/reassign")
    public ResponseEntity<?> manualReassign(
            @PathVariable String id,
            @RequestBody ManualReassignRequest request
    ) {
        log.debug("POST /orders/{}/reassign: newAgentId={}", id, request.getNewAgentId());

        if (request.getNewAgentId() == null || request.getNewAgentId().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("New agent ID is required"));
        }

        try {
            Order order = orderService.findById(id)
                    .orElseGet(() -> {
                        log.warn("Order not found for reassignment: {}", id);
                        return null;
                    });

            if (order == null) {
                return ResponseEntity.notFound().build();
            }

            // Update order with new agent and mark as REASSIGNED
            String oldAgent = order.getAssignedAgentId();
            Order updated = orderService.reassignToAgent(id, request.getNewAgentId());

            log.info(
                "Order manually reassigned: orderId={}, oldAgent={}, newAgent={}, status={}",
                id,
                oldAgent,
                request.getNewAgentId(),
                OrderStatus.REASSIGNED
            );

            return ResponseEntity.ok(updated);

        } catch (Exception e) {
            log.error("Failed to reassign order {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to reassign order"));
        }
    }

    // ============ DTOs ============

    public static class CreateOrderRequest {
        private String description;
        private String assignedAgentId;

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getAssignedAgentId() { return assignedAgentId; }
        public void setAssignedAgentId(String assignedAgentId) { this.assignedAgentId = assignedAgentId; }
    }

    public static class UpdateOrderStatusRequest {
        private String status;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class ManualReassignRequest {
        private String newAgentId;

        public String getNewAgentId() { return newAgentId; }
        public void setNewAgentId(String newAgentId) { this.newAgentId = newAgentId; }
    }

    public static class ErrorResponse {
        private String message;

        public ErrorResponse(String message) { this.message = message; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
