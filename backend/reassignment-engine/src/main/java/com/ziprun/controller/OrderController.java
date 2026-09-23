package com.ziprun.controller;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.repository.OrderRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.routing.RoutingService;
import com.ziprun.routing.RoutingResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REST API for order management.
 *
 * Endpoints:
 * POST /orders - Create order (pre-assigned to agent)
 * GET /orders - List orders (filterable by status)
 * GET /orders/{id} - Get single order
 * POST /orders/{id}/suggest - On-demand reassignment suggestion (calls routing engine)
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository orderRepository;
    private final RoutingService routingService;
    private final ReassignmentSuggestionRepository suggestionRepository;

    public OrderController(
            OrderRepository orderRepository,
            RoutingService routingService,
            ReassignmentSuggestionRepository suggestionRepository
    ) {
        this.orderRepository = orderRepository;
        this.routingService = routingService;
        this.suggestionRepository = suggestionRepository;
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody CreateOrderRequest request) {
        if (request.getAssignedAgentId() == null || request.getAssignedAgentId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getDescription() == null || request.getDescription().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Order order = new Order();
        order.setId("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setDescription(request.getDescription());
        order.setAssignedAgentId(request.getAssignedAgentId());
        order.setStatus(OrderStatus.ASSIGNED);
        order.setCreatedAt(LocalDateTime.now());

        Order saved = orderRepository.save(order);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping
    public ResponseEntity<List<Order>> listOrders(
            @RequestParam(name = "status", required = false) String statusStr
    ) {
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                OrderStatus status = OrderStatus.valueOf(statusStr.toUpperCase());
                return ResponseEntity.ok(orderRepository.findByStatus(status));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.ok(orderRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable String id) {
        return orderRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/suggest")
    public ResponseEntity<?> suggestReassignment(@PathVariable String id) {
        return orderRepository.findById(id)
                .map(order -> {
                    RoutingResult result = routingService.route(order);

                    if (result.getRecommendedAgentId() == null) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(new ErrorResponse("No available agents for reassignment"));
                    }

                    ReassignmentSuggestion suggestion = new ReassignmentSuggestion();
                    suggestion.setId("SUGG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
                    suggestion.setOrderId(order.getId());
                    suggestion.setRecommendedAgentId(result.getRecommendedAgentId());
                    suggestion.setConfidence(result.getConfidence());
                    suggestion.setReasoning(result.getReasoning());
                    suggestion.setStatus(SuggestionStatus.PENDING);
                    suggestion.setTriggerReason(TriggerReason.INITIAL);
                    suggestion.setCreatedAt(LocalDateTime.now());

                    ReassignmentSuggestion saved = suggestionRepository.save(suggestion);
                    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Order> updateOrderStatus(
            @PathVariable String id,
            @RequestBody UpdateOrderStatusRequest request
    ) {
        return orderRepository.findById(id)
                .map(order -> {
                    try {
                        OrderStatus status = OrderStatus.valueOf(request.getStatus().toUpperCase());
                        order.setStatus(status);
                        return ResponseEntity.ok(orderRepository.save(order));
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().<Order>build();
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // DTO Classes
    public static class CreateOrderRequest {
        private String description;
        private String assignedAgentId;

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getAssignedAgentId() {
            return assignedAgentId;
        }

        public void setAssignedAgentId(String assignedAgentId) {
            this.assignedAgentId = assignedAgentId;
        }
    }

    public static class UpdateOrderStatusRequest {
        private String status;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class ErrorResponse {
        private String message;

        public ErrorResponse(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
