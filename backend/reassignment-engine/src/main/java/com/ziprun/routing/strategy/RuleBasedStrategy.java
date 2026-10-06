package com.ziprun.routing.strategy;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingStrategy;
import com.ziprun.routing.Zones;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rule-Based Routing Strategy: deterministic, no external dependencies.
 *
 * Decision Rule: rank agents by score = effective load (active orders + suggestions
 * already pending for them) + a zone penalty for how far they are from the pickup
 * (same zone 0, neighbouring 1, unknown 2, further 3). Lowest score first; ties
 * broken by raw load, then agent ID. With no pickup zone on the order, the score is
 * just the load.
 *
 * Characteristics:
 * - Deterministic: same inputs always give the same ranking and confidence
 * - No external dependencies, so it is the fallback whenever the AI fails
 * - Confidence reflects how clear-cut the choice is (score gap to the runner-up),
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
            .sorted(Comparator.<Agent>comparingInt(a -> score(order, a, context))
                .thenComparingInt(context::effectiveLoad)
                .thenComparing(Agent::getId))
            .toList();

        int bestScore = score(order, ranked.get(0), context);
        List<RoutingResult> results = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Agent agent = ranked.get(i);
            results.add(new RoutingResult(
                agent.getId(),
                confidence(i, ranked, bestScore, order, context),
                buildReasoning(i, ranked, order, context),
                NAME
            ));
        }
        return results;
    }

    @Override
    public String getName() {
        return NAME;
    }

    static int score(Order order, Agent agent, RoutingContext ctx) {
        return ctx.effectiveLoad(agent) + zonePenalty(order, agent);
    }

    private static int zonePenalty(Order order, Agent agent) {
        return order.getPickupZone() == null ? 0 : Zones.distance(order.getPickupZone(), agent.getCurrentZone()).penalty;
    }

    /**
     * Top pick: 0.70 with no alternative, 0.60 on a tie, otherwise 0.75 + 0.05 per
     * point of score gap to the runner-up (capped at 0.95).
     * Lower-ranked agents: 0.5 scaled down by how much worse they score than the best.
     */
    private double confidence(int rank, List<Agent> ranked, int bestScore, Order order, RoutingContext ctx) {
        if (rank == 0) {
            if (ranked.size() == 1) {
                return 0.70;
            }
            int gap = score(order, ranked.get(1), ctx) - bestScore;
            return gap == 0 ? 0.60 : Math.min(95, 75 + 5 * gap) / 100.0;
        }
        int extra = score(order, ranked.get(rank), ctx) - bestScore;
        return Math.round(50.0 / (1 + extra)) / 100.0;
    }

    /**
     * Plain-English reasoning; ops reads this verbatim in the UI.
     */
    private String buildReasoning(int rank, List<Agent> ranked, Order order, RoutingContext ctx) {
        Agent agent = ranked.get(rank);
        StringBuilder sb = new StringBuilder();
        if (ctx.isRecovery()) {
            sb.append(String.format("Recovery: %s went offline leaving %d stranded order(s). ",
                ctx.failedAgentName(), ctx.strandedOrderCount()));
        } else if (ctx.isSlaRisk()) {
            sb.append("Deadline at risk with the current agent. ");
        }
        sb.append(String.format("%s has %s%s.", agent.getName(), describeLoad(agent, ctx), describeZone(order, agent)));

        if (rank == 0) {
            if (ranked.size() == 1) {
                sb.append(" They are the only available agent.");
            } else {
                Agent runnerUp = ranked.get(1);
                int gap = score(order, runnerUp, ctx) - score(order, agent, ctx);
                String basis = order.getPickupZone() == null ? "load" : "load and distance";
                sb.append(gap == 0
                    ? String.format(" Tied with %s on %s; picked by agent ID.", runnerUp.getName(), basis)
                    : String.format(" Best on %s of %d available agents; next best is %s with %s%s.",
                        basis, ranked.size(), runnerUp.getName(), describeLoad(runnerUp, ctx), describeZone(order, runnerUp)));
            }
        } else {
            sb.append(String.format(" Ranked #%d.", rank + 1));
        }
        return sb.toString();
    }

    private String describeLoad(Agent agent, RoutingContext ctx) {
        int pending = ctx.pendingFor(agent);
        String active = agent.getActiveOrderCount() + " active order" + (agent.getActiveOrderCount() == 1 ? "" : "s");
        String load = pending == 0 ? active : active + " + " + pending + " pending suggestion" + (pending == 1 ? "" : "s");
        int capacity = ctx.capacityOf(agent);
        return capacity > 0 ? load + " (" + ctx.loadOfCapacity(agent) + " of capacity)" : load;
    }

    private String describeZone(Order order, Agent agent) {
        if (order.getPickupZone() == null) {
            return "";
        }
        String pickup = Zones.name(order.getPickupZone());
        return switch (Zones.distance(order.getPickupZone(), agent.getCurrentZone())) {
            case SAME -> ", already in " + pickup + " (the pickup zone)";
            case NEIGHBOUR -> ", in " + Zones.name(agent.getCurrentZone()) + " next to " + pickup;
            case FAR -> ", in " + Zones.name(agent.getCurrentZone()) + ", away from " + pickup;
            case UNKNOWN -> ", location unknown";
        };
    }
}
