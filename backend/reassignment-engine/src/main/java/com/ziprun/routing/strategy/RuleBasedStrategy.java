package com.ziprun.routing.strategy;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingStrategy;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rule-Based Routing Strategy: deterministic, no external dependencies.
 *
 * Decision Rule: rank agents by effective load (active orders + suggestions
 * already pending for them), fewest first; ties broken by agent ID.
 *
 * Characteristics:
 * - Deterministic: same inputs always give the same ranking and confidence
 * - No external dependencies, so it is the fallback whenever the AI fails
 * - Confidence reflects how clear-cut the choice is (load gap to the runner-up),
 *   not a fixed or random number
 *
 * Design Note: This strategy is stateless and thread-safe.
 */
@Component(RuleBasedStrategy.NAME)
public class RuleBasedStrategy implements RoutingStrategy {
    public static final String NAME = "rule-based";

    @Override
    public List<RoutingResult> recommend(Order order, List<Agent> availableAgents, RoutingContext context) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            return List.of();
        }

        List<Agent> ranked = availableAgents.stream()
            .sorted(Comparator.comparingInt(context::effectiveLoad).thenComparing(Agent::getId))
            .toList();

        int bestLoad = context.effectiveLoad(ranked.get(0));
        List<RoutingResult> results = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Agent agent = ranked.get(i);
            results.add(new RoutingResult(
                agent.getId(),
                confidence(i, ranked, bestLoad, context),
                buildReasoning(i, ranked, context),
                NAME
            ));
        }
        return results;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Top pick: 0.70 with no alternative, 0.60 on a tie, otherwise 0.75 + 0.05 per
     * order of load gap to the runner-up (capped at 0.95).
     * Lower-ranked agents: 0.5 scaled down by how much heavier they are than the best.
     */
    private double confidence(int rank, List<Agent> ranked, int bestLoad, RoutingContext ctx) {
        if (rank == 0) {
            if (ranked.size() == 1) {
                return 0.70;
            }
            int gap = ctx.effectiveLoad(ranked.get(1)) - bestLoad;
            return gap == 0 ? 0.60 : Math.min(95, 75 + 5 * gap) / 100.0;
        }
        int extra = ctx.effectiveLoad(ranked.get(rank)) - bestLoad;
        return Math.round(50.0 / (1 + extra)) / 100.0;
    }

    /**
     * Plain-English reasoning; ops reads this verbatim in the UI.
     */
    private String buildReasoning(int rank, List<Agent> ranked, RoutingContext ctx) {
        Agent agent = ranked.get(rank);
        StringBuilder sb = new StringBuilder();
        if (ctx.isRecovery()) {
            sb.append(String.format("Recovery: %s went offline leaving %d stranded order(s). ",
                ctx.failedAgentName(), ctx.strandedOrderCount()));
        }
        sb.append(String.format("%s has %s.", agent.getName(), describeLoad(agent, ctx)));

        if (rank == 0) {
            if (ranked.size() == 1) {
                sb.append(" They are the only available agent.");
            } else {
                Agent runnerUp = ranked.get(1);
                int gap = ctx.effectiveLoad(runnerUp) - ctx.effectiveLoad(agent);
                sb.append(gap == 0
                    ? String.format(" Tied with %s on load; picked by agent ID.", runnerUp.getName())
                    : String.format(" Lightest load of %d available agents; next best is %s with %s.",
                        ranked.size(), runnerUp.getName(), describeLoad(runnerUp, ctx)));
            }
        } else {
            sb.append(String.format(" Ranked #%d by load.", rank + 1));
        }
        return sb.toString();
    }

    private String describeLoad(Agent agent, RoutingContext ctx) {
        int pending = ctx.pendingFor(agent);
        String active = agent.getActiveOrderCount() + " active order" + (agent.getActiveOrderCount() == 1 ? "" : "s");
        return pending == 0 ? active : active + " + " + pending + " pending suggestion" + (pending == 1 ? "" : "s");
    }
}
