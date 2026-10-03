package com.ziprun.controller;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.TriggerReason;
import com.ziprun.exception.InvalidStateException;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingService;
import com.ziprun.service.order.OrderService;
import com.ziprun.service.suggestion.SuggestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Order Controller: REST API for order management.
 *
 * RESPONSIBILITY: Handle HTTP concerns only
 * - Parse and validate the request shape (@Valid)
 * - Call business logic (OrderService, RoutingService, SuggestionService)
 * - Choose the HTTP status for success; errors are mapped by GlobalExceptionHandler
 *
 * Endpoints:
 * POST   /orders              - Create order pre-assigned to an agent (201)
 * GET    /orders?status=      - List orders (optionally filtered by status)
 * GET    /orders/{id}         - Get single order
 * PATCH  /orders/{id}/status  - Update order status (state machine enforced)
 * POST   /orders/{id}/suggest - Run active routing strategy, persist + return suggestion (201)
 * POST   /orders/{id}/reassign- Manual override by ops
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
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Order createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(request.description(), request.assignedAgentId());
    }

    /**
     * GET /orders - List all orders, optionally filtered by status.
     * Query params: ?status=ASSIGNED | REASSIGNMENT_PENDING | REASSIGNED | DELIVERED
     */
    @GetMapping
    public List<Order> listOrders(@RequestParam(name = "status", required = false) String status) {
        if (status == null || status.isBlank()) {
            return orderService.findAll();
        }
        return orderService.findByStatus(EnumParam.parse(OrderStatus.class, status, "order status"));
    }

    @GetMapping("/{id}")
    public Order getOrder(@PathVariable String id) {
        return orderService.getById(id);
    }

    /**
     * PATCH /orders/{id}/status - e.g. { "status": "DELIVERED" }
     */
    @PatchMapping("/{id}/status")
    public Order updateOrderStatus(@PathVariable String id, @Valid @RequestBody UpdateStatusRequest request) {
        return orderService.updateStatus(id, EnumParam.parse(OrderStatus.class, request.status(), "order status"));
    }

    /**
     * POST /orders/{id}/suggest - On-demand suggestion (triggerReason = INITIAL).
     *
     * Synchronous by nature: the caller asked for a suggestion and gets it back.
     * The wait is bounded by llm.timeout-ms per provider, and any AI failure
     * still returns a rule-based suggestion.
     */
    @PostMapping("/{id}/suggest")
    @ResponseStatus(HttpStatus.CREATED)
    public ReassignmentSuggestion suggestReassignment(@PathVariable String id) {
        Order order = orderService.getById(id);
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new InvalidStateException("Order " + id + " is already DELIVERED");
        }

        RoutingResult result = routingService.route(order, RoutingContext.initial())
            .orElseThrow(() -> new InvalidStateException("No AVAILABLE agents to recommend for order " + id));

        log.debug("Suggestion for order {}: {}", id, result);
        return suggestionService.createSuggestion(order.getId(), result, TriggerReason.INITIAL);
    }

    /**
     * POST /orders/{id}/reassign - Manual reassignment by ops (override AI).
     * Request: { "newAgentId": "AGT-002" }
     */
    @PostMapping("/{id}/reassign")
    public Order manualReassign(@PathVariable String id, @Valid @RequestBody ManualReassignRequest request) {
        return orderService.reassignToAgent(id, request.newAgentId());
    }

    // ============ DTOs ============

    public record CreateOrderRequest(@NotBlank String description, @NotBlank String assignedAgentId) {
    }

    public record UpdateStatusRequest(@NotBlank String status) {
    }

    public record ManualReassignRequest(@NotBlank String newAgentId) {
    }
}
