package com.ziprun.controller;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.repository.OrderRepository;
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

    public OrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
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
    public ResponseEntity<String> suggestReassignment(@PathVariable String id) {
        // TODO: Wire routing engine here
        // - Get order by ID
        // - Get available agents
        // - Call active routing strategy
        // - Persist ReassignmentSuggestion
        // - Return suggestion
        return ResponseEntity.ok("{}");
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
}
