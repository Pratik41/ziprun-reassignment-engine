package com.ziprun.controller;

import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * Routing Controller: inspect and switch the active routing strategy at runtime.
 *
 * GET /routing/strategy  - { "active": "ai", "available": ["ai", "rule-based"] }
 * PUT /routing/strategy  - body { "strategy": "rule-based" } switches with no restart
 * POST /routing/recommend - body { "description": "..." } top agents for a new order (nothing is saved)
 */
@RestController
@RequestMapping("/routing")
public class RoutingController {

    private static final int MAX_RECOMMENDATIONS = 3;

    private final RoutingService routingService;

    public RoutingController(RoutingService routingService) {
        this.routingService = routingService;
    }

    @GetMapping("/strategy")
    public StrategyResponse getStrategy() {
        return current();
    }

    @PutMapping("/strategy")
    public StrategyResponse switchStrategy(@Valid @RequestBody SwitchStrategyRequest request) {
        routingService.switchStrategy(request.strategy());
        return current();
    }

    /**
     * Recommendation for the "New order" dialog: the active strategy ranks the
     * Available agents by effective load (and, for AI, the description), best first.
     * Bounded by llm.timeout-ms per provider; an AI failure still returns rule-based picks.
     */
    @PostMapping("/recommend")
    public RecommendResponse recommend(@Valid @RequestBody(required = false) RecommendRequest request) {
        String description = request == null ? null : request.description();
        List<RoutingResult> options = routingService.recommendForNewOrder(description, MAX_RECOMMENDATIONS);
        return new RecommendResponse(routingService.getActiveStrategyName(), options);
    }

    private StrategyResponse current() {
        return new StrategyResponse(routingService.getActiveStrategyName(), routingService.getAvailableStrategies());
    }

    public record SwitchStrategyRequest(@NotBlank String strategy) {
    }

    public record StrategyResponse(String active, Set<String> available) {
    }

    public record RecommendRequest(@Size(max = 500) String description) {
    }

    public record RecommendResponse(String strategy, List<RoutingResult> options) {
    }
}
