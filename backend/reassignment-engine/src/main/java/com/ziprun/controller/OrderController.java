package com.ziprun.controller;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.TriggerReason;
import com.ziprun.exception.InvalidStateException;
import com.ziprun.exception.StaleRecommendationException;
import com.ziprun.routing.ReasoningListener;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingService;
import com.ziprun.service.order.OrderService;
import com.ziprun.service.suggestion.SuggestionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ziprun.config.StreamWorkers;
import com.ziprun.controller.dto.OrderView;
import com.ziprun.controller.dto.SuggestionView;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Order Controller: REST API for order management.
 *
 * RESPONSIBILITY: Handle HTTP concerns only
 * - Parse and validate the request shape (@Valid)
 * - Call business logic (OrderService, RoutingService, SuggestionService)
 * - Choose the HTTP status for success; errors are mapped by GlobalExceptionHandler
 *
 * Endpoints:
 * POST   /orders              - Create order pre-assigned to an agent (201); records the recommendation shown, if any
 * GET    /orders?status=      - List orders (optionally filtered by status)
 * GET    /orders/{id}         - Get single order
 * PATCH  /orders/{id}/status  - Update order status (state machine enforced)
 * POST   /orders/{id}/suggest - Run active routing strategy, persist + return suggestion (201)
 * POST   /orders/{id}/suggest/stream - Same, streaming the AI's reasoning as SSE
 * POST   /orders/{id}/reassign- Manual override by ops
 * POST   /orders/{id}/keep    - Keep with original agent once they're back online
 */
@RestController
@RequestMapping("/orders")
public class OrderController {
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private static final long STREAM_TIMEOUT_MS = 60_000;

    private final OrderService orderService;
    private final SuggestionService suggestionService;
    private final RoutingService routingService;
    private final StreamWorkers streamWorkers;

    public OrderController(
            OrderService orderService,
            SuggestionService suggestionService,
            RoutingService routingService,
            StreamWorkers streamWorkers
    ) {
        this.orderService = orderService;
        this.suggestionService = suggestionService;
        this.routingService = routingService;
        this.streamWorkers = streamWorkers;
    }

    /**
     * POST /orders - Create order pre-assigned to an agent.
     * Simulates morning manual assignment workflow.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return OrderView.from(orderService.createOrder(new OrderService.NewOrder(request.description(), request.assignedAgentId(),
            request.recommendedAgentId(), request.pickupZone(), request.dropoffZone(), request.slaMinutes())));
    }

    /**
     * GET /orders - List all orders, optionally filtered by status.
     * Query params: ?status=ASSIGNED | REASSIGNMENT_PENDING | REASSIGNED | DELIVERED
     */
    @GetMapping
    public List<OrderView> listOrders(@RequestParam(name = "status", required = false) String status,
                                @RequestParam(name = "page", required = false) Integer page,
                                @RequestParam(name = "size", required = false) Integer size,
                                HttpServletResponse response) {
        OrderStatus filter = status == null || status.isBlank() ? null : EnumParam.parse(OrderStatus.class, status, "order status");
        return Paging.respond(orderService.list(filter, Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
            OrderView::from, response);
    }

    @GetMapping("/{id}")
    public OrderView getOrder(@PathVariable String id) {
        return OrderView.from(orderService.getById(id));
    }

    /**
     * PATCH /orders/{id}/status - e.g. { "status": "DELIVERED" }
     */
    @PatchMapping("/{id}/status")
    public OrderView updateOrderStatus(@PathVariable String id, @Valid @RequestBody UpdateStatusRequest request) {
        return OrderView.from(orderService.updateStatus(id, EnumParam.parse(OrderStatus.class, request.status(), "order status")));
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
    public SuggestionView suggestReassignment(@PathVariable String id) {
        return SuggestionView.from(routeAndPersist(suggestableOrder(id), RoutingContext.initial()));
    }

    /**
     * POST /orders/{id}/suggest/stream - Same as /suggest, streamed as Server-Sent Events.
     *
     * Events (data is JSON):
     *   start      {orderId, strategy}
     *   token      {text}      next fragment of the AI's reasoning, as it is generated
     *   restart    {reason}    discard streamed text (provider failed / fallback to rule-based)
     *   suggestion {...}       the persisted suggestion (same body as /suggest); stream ends
     *   error      {message}   nothing was persisted; stream ends
     *
     * Validation and fallback are identical to /suggest: streaming only adds a
     * ReasoningListener to the RoutingContext.
     */
    @PostMapping(value = "/{id}/suggest/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSuggestion(@PathVariable String id) {
        Order order = suggestableOrder(id); // 404/409 as plain JSON before the stream opens
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SseSender sse = new SseSender(emitter);

        // its own pool, so streams never wait behind the background re-planning loop
        streamWorkers.runStream(() -> {
            try {
                sse.send("start", Map.of("orderId", id, "strategy", routingService.getActiveStrategyName()));
                ReasoningListener listener = new ReasoningListener() {
                    @Override
                    public void token(String text) {
                        sse.send("token", Map.of("text", text));
                    }

                    @Override
                    public void restart(String reason) {
                        sse.send("restart", Map.of("reason", reason));
                    }
                };
                ReassignmentSuggestion saved = routeAndPersist(order, RoutingContext.initial().withListener(listener));
                sse.send("suggestion", SuggestionView.from(saved));
            } catch (Exception e) {
                log.warn("Streaming suggestion for order {} failed: {}", id, e.getMessage());
                sse.send("error", Map.of("message", e.getMessage() == null ? "Suggestion failed" : e.getMessage()));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private Order suggestableOrder(String id) {
        Order order = orderService.getById(id);
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new InvalidStateException("Order " + id + " is already DELIVERED");
        }
        return order;
    }

    private ReassignmentSuggestion routeAndPersist(Order order, RoutingContext context) {
        for (int attempt = 1; ; attempt++) {
            RoutingResult result = routingService.route(order, context)
                .orElseThrow(() -> new InvalidStateException("No AVAILABLE agents to recommend for order " + order.getId()));
            log.debug("Suggestion for order {}: {}", order.getId(), result);
            try {
                return suggestionService.createSuggestion(order.getId(), result, TriggerReason.INITIAL);
            } catch (StaleRecommendationException e) {
                if (attempt >= 2) {
                    throw e;
                }
                // The roster changed while the LLM was thinking; route once more
                context.listener().restart(e.getMessage() + ", re-routing");
            }
        }
    }

    /**
     * Sends SSE events; once the client has gone away, further sends are dropped
     * (the suggestion is still persisted, ops asked for it).
     */
    private static final class SseSender {
        private final SseEmitter emitter;
        private volatile boolean clientGone;

        SseSender(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onError(e -> clientGone = true);
            emitter.onTimeout(() -> clientGone = true);
        }

        void send(String event, Object data) {
            if (clientGone) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                clientGone = true;
                log.debug("SSE client disconnected: {}", e.getMessage());
            }
        }
    }

    /**
     * POST /orders/{id}/reassign - Manual reassignment by ops (override AI); expires the order's open suggestions.
     * Request: { "newAgentId": "AGT-002" }
     */
    @PostMapping("/{id}/reassign")
    public OrderView manualReassign(@PathVariable String id, @Valid @RequestBody ManualReassignRequest request) {
        return OrderView.from(suggestionService.reassignManually(id, request.newAgentId()));
    }

    /**
     * POST /orders/{id}/keep - The order's original agent is back online: keep the
     * order with them (REASSIGNMENT_PENDING -> ASSIGNED) and expire its open suggestions.
     */
    @PostMapping("/{id}/keep")
    public OrderView keepWithCurrentAgent(@PathVariable String id) {
        return OrderView.from(suggestionService.keepWithCurrentAgent(id));
    }

    // ============ DTOs ============

    /**
     * recommendedAgentId: optional, the top pick from POST /routing/recommend that ops was shown.
     * pickupZone / dropoffZone: optional Zones ids. slaMinutes: optional deadline from now
     * (null = default, 0 = none).
     */
    public record CreateOrderRequest(@NotBlank @Size(max = 500) String description, @NotBlank String assignedAgentId,
                                     String recommendedAgentId, String pickupZone, String dropoffZone,
                                     Integer slaMinutes) {
    }

    public record UpdateStatusRequest(@NotBlank String status) {
    }

    public record ManualReassignRequest(@NotBlank String newAgentId) {
    }
}
