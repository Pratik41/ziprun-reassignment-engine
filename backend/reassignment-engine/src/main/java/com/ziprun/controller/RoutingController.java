package com.ziprun.controller;

import com.ziprun.routing.RoutingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * Routing Controller: inspect and switch the active routing strategy at runtime.
 *
 * GET /routing/strategy  - { "active": "ai", "available": ["ai", "rule-based"] }
 * PUT /routing/strategy  - body { "strategy": "rule-based" } switches with no restart
 */
@RestController
@RequestMapping("/routing")
public class RoutingController {

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

    private StrategyResponse current() {
        return new StrategyResponse(routingService.getActiveStrategyName(), routingService.getAvailableStrategies());
    }

    public record SwitchStrategyRequest(@NotBlank String strategy) {
    }

    public record StrategyResponse(String active, Set<String> available) {
    }
}
