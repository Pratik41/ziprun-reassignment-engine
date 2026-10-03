package com.ziprun.routing;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.Order;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.routing.strategy.RuleBasedStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routing Service: the single entry point for "who should take this order?"
 *
 * Responsibilities (and only these):
 * - Build the candidate list (AVAILABLE agents, minus the order's current agent)
 * - Attach the pending-load snapshot to the RoutingContext
 * - Resolve the active strategy from the auto-wired bean map and run it
 * - Safety net: if ANY strategy throws, fall back to rule-based so callers
 *   never get nothing because a strategy had a bug
 *
 * Runtime switchability: the active strategy name lives in an AtomicReference,
 * seeded from routing.strategy at startup and changed via PUT /routing/strategy.
 * Each route() call reads it once, so HTTP and async callers both see a switch
 * on their very next call with no restart.
 *
 * It does not persist suggestions, publish events or know about prompts.
 */
@Service
public class RoutingService {
    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final Map<String, RoutingStrategy> strategies;
    private final AgentRepository agentRepository;
    private final ReassignmentSuggestionRepository suggestionRepository;
    private final AtomicReference<String> activeStrategyName;

    public RoutingService(
            Map<String, RoutingStrategy> strategies,
            AgentRepository agentRepository,
            ReassignmentSuggestionRepository suggestionRepository,
            @Value("${routing.strategy:rule-based}") String configuredStrategy
    ) {
        this.strategies = Map.copyOf(strategies);
        this.agentRepository = agentRepository;
        this.suggestionRepository = suggestionRepository;

        // Fail at startup, not on the first request, if config names an unknown strategy
        if (!this.strategies.containsKey(RuleBasedStrategy.NAME)) {
            throw new IllegalStateException("Fallback strategy '" + RuleBasedStrategy.NAME + "' is not registered");
        }
        if (!this.strategies.containsKey(configuredStrategy)) {
            throw new IllegalStateException(String.format(
                "routing.strategy='%s' is not a registered strategy. Available: %s",
                configuredStrategy, getAvailableStrategies()));
        }
        this.activeStrategyName = new AtomicReference<>(configuredStrategy);
        log.info("Routing strategies registered: {}. Active: {}", getAvailableStrategies(), configuredStrategy);
    }

    /**
     * Best recommendation for the order, or empty if no agent can take it.
     */
    public Optional<RoutingResult> route(Order order, RoutingContext context) {
        return rank(order, context).stream().findFirst();
    }

    /**
     * Full ranked list from the active strategy (best first).
     */
    public List<RoutingResult> rank(Order order, RoutingContext context) {
        List<Agent> candidates = agentRepository.findByStatus(AgentStatus.AVAILABLE).stream()
            .filter(agent -> !agent.getId().equals(order.getAssignedAgentId()))
            .toList();
        RoutingContext enriched = context.withPendingLoad(pendingLoadByAgent());

        String strategyName = activeStrategyName.get();
        RoutingStrategy strategy = strategies.get(strategyName);

        List<RoutingResult> results;
        try {
            results = strategy.recommend(order, candidates, enriched);
        } catch (RuntimeException e) {
            log.error("Strategy '{}' threw for order {}. Falling back to rule-based.", strategyName, order.getId(), e);
            results = strategies.get(RuleBasedStrategy.NAME).recommend(order, candidates, enriched).stream()
                .map(r -> r.withSource(RuleBasedStrategy.NAME + " (fallback: " + strategyName + " failed)"))
                .toList();
        }
        results = results == null ? List.of() : results;

        log.debug("Routing order {} [{}] via '{}' over {} candidates -> {}",
            order.getId(), context.trigger(), strategyName, candidates.size(),
            results.isEmpty() ? "no recommendation" : results.get(0));
        return results;
    }

    public String getActiveStrategyName() {
        return activeStrategyName.get();
    }

    public Set<String> getAvailableStrategies() {
        return new TreeSet<>(strategies.keySet());
    }

    /**
     * Switches the active strategy for all subsequent routing calls (HTTP and async).
     *
     * @throws IllegalArgumentException if no strategy with that name is registered
     */
    public void switchStrategy(String name) {
        if (name == null || !strategies.containsKey(name)) {
            throw new IllegalArgumentException(String.format(
                "Unknown routing strategy '%s'. Available: %s", name, getAvailableStrategies()));
        }
        String previous = activeStrategyName.getAndSet(name);
        log.info("Routing strategy switched at runtime: {} -> {}", previous, name);
    }

    private Map<String, Integer> pendingLoadByAgent() {
        Map<String, Integer> load = new HashMap<>();
        for (Object[] row : suggestionRepository.countPendingByRecommendedAgent()) {
            load.put((String) row[0], ((Number) row[1]).intValue());
        }
        return load;
    }
}
