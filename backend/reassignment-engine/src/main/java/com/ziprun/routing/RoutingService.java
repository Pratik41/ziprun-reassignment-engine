package com.ziprun.routing;

import com.ziprun.domain.Activity;
import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.AppSetting;
import com.ziprun.domain.Order;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.AppSettingRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.routing.strategy.RuleBasedStrategy;
import com.ziprun.service.activity.ActivityService;
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
 * - Confidence guardrail for thin rosters / single candidates (applyRosterLimits)
 * - Time each routing call (shown in the Insights metrics)
 * - Preview rankings for a new order before it exists (recommendForNewOrder)
 *
 * Runtime switchability: the active strategy name lives in an AtomicReference,
 * changed via PUT /routing/strategy and saved in app_settings so it survives
 * restarts (routing.strategy is only the first-run default). Each route() call
 * reads it once, so HTTP and async callers both see a switch on their very next call.
 *
 * It does not persist suggestions, publish events or know about prompts.
 */
@Service
public class RoutingService {
    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    /** Max confidence when there are fewer candidates than stranded orders in the batch. */
    static final double THIN_ROSTER_CAP = 0.60;
    /** Max confidence when there is only one candidate (nothing to compare against). */
    static final double SINGLE_CANDIDATE_CAP = 0.75;

    private final Map<String, RoutingStrategy> strategies;
    private final AgentRepository agentRepository;
    private final ReassignmentSuggestionRepository suggestionRepository;
    private final AppSettingRepository settings;
    private final ActivityService activity;
    private final AtomicReference<String> activeStrategyName;

    public RoutingService(
            Map<String, RoutingStrategy> strategies,
            AgentRepository agentRepository,
            ReassignmentSuggestionRepository suggestionRepository,
            AppSettingRepository settings,
            ActivityService activity,
            @Value("${routing.strategy:rule-based}") String configuredStrategy
    ) {
        this.strategies = Map.copyOf(strategies);
        this.agentRepository = agentRepository;
        this.suggestionRepository = suggestionRepository;
        this.settings = settings;
        this.activity = activity;

        // Fail at startup, not on the first request, if config names an unknown strategy
        if (!this.strategies.containsKey(RuleBasedStrategy.NAME)) {
            throw new IllegalStateException("Fallback strategy '" + RuleBasedStrategy.NAME + "' is not registered");
        }
        if (!this.strategies.containsKey(configuredStrategy)) {
            throw new IllegalStateException(String.format(
                "routing.strategy='%s' is not a registered strategy. Available: %s",
                configuredStrategy, getAvailableStrategies()));
        }

        // A choice made in the UI survives restarts; config is only the first-run default
        String saved = settings.findById(AppSetting.ROUTING_STRATEGY).map(AppSetting::getValue).orElse(null);
        String initial = configuredStrategy;
        if (saved != null && this.strategies.containsKey(saved)) {
            initial = saved;
        } else if (saved != null) {
            log.warn("Saved routing strategy '{}' is no longer registered; using '{}'", saved, configuredStrategy);
        }
        this.activeStrategyName = new AtomicReference<>(initial);
        log.info("Routing strategies registered: {}. Active: {} ({})", getAvailableStrategies(), initial,
            initial.equals(saved) ? "saved choice" : "routing.strategy");
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

        long started = System.currentTimeMillis();
        List<RoutingResult> results;
        try {
            results = strategy.recommend(order, candidates, enriched);
        } catch (RuntimeException e) {
            log.error("Strategy '{}' threw for order {}. Falling back to rule-based.", strategyName, order.getId(), e);
            enriched.listener().restart("Strategy " + strategyName + " failed, using rule-based routing");
            results = strategies.get(RuleBasedStrategy.NAME).recommend(order, candidates, enriched).stream()
                .map(r -> r.withSource(RuleBasedStrategy.NAME + " (fallback: " + strategyName + " failed)"))
                .toList();
        }
        long elapsed = System.currentTimeMillis() - started;
        results = applyRosterLimits(results == null ? List.of() : results, candidates.size(), enriched).stream()
            .map(r -> r.withRoutingMillis(elapsed))
            .toList();

        log.debug("Routing order {} [{}] via '{}' over {} candidates -> {}",
            order.getId(), context.trigger(), strategyName, candidates.size(),
            results.isEmpty() ? "no recommendation" : results.get(0));
        return results;
    }

    /**
     * Ranks agents for an order that hasn't been created yet (the "New order" dialog).
     * Same strategies, load snapshot and guardrails as a real routing call; nothing is saved.
     */
    public List<RoutingResult> recommendForNewOrder(String description, int limit) {
        Order draft = new Order();
        draft.setId("NEW-ORDER");
        draft.setDescription(description == null || description.isBlank() ? "(no description yet)" : description.trim());
        return rank(draft, RoutingContext.initial()).stream().limit(limit).toList();
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
        settings.save(new AppSetting(AppSetting.ROUTING_STRATEGY, name));
        if (!name.equals(previous)) {
            activity.record(Activity.Type.STRATEGY_SWITCHED, Activity.Actor.OPS,
                String.format("Routing strategy switched: %s → %s", previous, name));
        }
        log.info("Routing strategy switched at runtime: {} -> {} (saved)", previous, name);
    }

    /**
     * Confidence guardrail applied to every strategy's answer, so no model (or rule)
     * can claim certainty the roster doesn't support:
     * - thin roster (recovery batch bigger than the candidate list): cap at 0.60 and
     *   tell ops in the reasoning that more agents are needed;
     * - a single candidate: cap at 0.75 (there was nothing to compare against).
     */
    private List<RoutingResult> applyRosterLimits(List<RoutingResult> results, int candidates, RoutingContext context) {
        if (results.isEmpty()) {
            return results;
        }
        boolean thinRoster = context.isRecovery() && candidates < context.strandedOrderCount();
        if (thinRoster) {
            String note = String.format(" Thin roster: %d stranded order(s) but only %d available agent(s); "
                + "consider making more agents available.", context.strandedOrderCount(), candidates);
            return results.stream().map(r -> r.capped(THIN_ROSTER_CAP, note)).toList();
        }
        if (candidates == 1) {
            return results.stream().map(r -> r.capped(SINGLE_CANDIDATE_CAP, "")).toList();
        }
        return results;
    }

    private Map<String, Integer> pendingLoadByAgent() {
        Map<String, Integer> load = new HashMap<>();
        for (Object[] row : suggestionRepository.countPendingByRecommendedAgent()) {
            load.put((String) row[0], ((Number) row[1]).intValue());
        }
        return load;
    }
}
