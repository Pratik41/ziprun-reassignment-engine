package com.ziprun.routing.strategy;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ziprun.TestFixtures.agent;
import static com.ziprun.TestFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedStrategyTest {

    private final RuleBasedStrategy strategy = new RuleBasedStrategy();
    private final Order order = order("ORD-1");

    @Test
    void nearbyAgentBeatsSlightlyLighterFarAgent() {
        Order koramangala = order("ORD-K");
        koramangala.setPickupZone("KORAMANGALA");
        Agent near = agent("AGT-1", 1);
        near.setCurrentZone("HSR_LAYOUT");     // neighbour: score 1 + 1 = 2
        Agent far = agent("AGT-2", 0);
        far.setCurrentZone("PEENYA");          // far: score 0 + 3 = 3
        Agent there = agent("AGT-3", 2);
        there.setCurrentZone("KORAMANGALA");   // same zone: score 2 + 0 = 2, heavier than AGT-1

        List<RoutingResult> results = strategy.recommend(koramangala, List.of(far, near, there), RoutingContext.initial());

        assertThat(results).extracting(RoutingResult::getRecommendedAgentId).containsExactly("AGT-1", "AGT-3", "AGT-2");
        assertThat(results.get(0).getReasoning()).contains("in HSR Layout next to Koramangala");
        assertThat(results.get(1).getReasoning()).contains("already in Koramangala");
    }

    @Test
    void withoutAPickupZoneLocationIsIgnored() {
        Agent far = agent("AGT-2", 0);
        far.setCurrentZone("PEENYA");
        Agent near = agent("AGT-1", 1);
        near.setCurrentZone("KORAMANGALA");

        assertThat(strategy.recommend(order, List.of(near, far), RoutingContext.initial()).get(0).getRecommendedAgentId())
            .isEqualTo("AGT-2");
    }

    @Test
    void ranksByActiveLoadThenId() {
        List<RoutingResult> results = strategy.recommend(order,
            List.of(agent("AGT-3", 2), agent("AGT-2", 0), agent("AGT-1", 0)), RoutingContext.initial());

        assertThat(results).extracting(RoutingResult::getRecommendedAgentId).containsExactly("AGT-1", "AGT-2", "AGT-3");
        assertThat(results.get(0).getSource()).isEqualTo("rule-based");
    }

    @Test
    void pendingSuggestionsCountTowardsLoad() {
        RoutingContext ctx = RoutingContext.initial().withPendingLoad(Map.of("AGT-1", 2));

        List<RoutingResult> results = strategy.recommend(order, List.of(agent("AGT-1", 0), agent("AGT-2", 1)), ctx);

        assertThat(results.get(0).getRecommendedAgentId()).isEqualTo("AGT-2");
        assertThat(results.get(1).getReasoning()).contains("2 pending suggestions");
    }

    @Test
    void confidenceIsDeterministicAndReflectsTheGap() {
        List<Agent> clearWinner = List.of(agent("AGT-1", 0), agent("AGT-2", 3));
        double first = strategy.recommend(order, clearWinner, RoutingContext.initial()).get(0).getConfidence();
        double second = strategy.recommend(order, clearWinner, RoutingContext.initial()).get(0).getConfidence();

        assertThat(first).isEqualTo(second).isEqualTo(0.90);
        assertThat(strategy.recommend(order, List.of(agent("AGT-1", 1), agent("AGT-2", 1)), RoutingContext.initial())
            .get(0).getConfidence()).isEqualTo(0.60);
        assertThat(strategy.recommend(order, List.of(agent("AGT-1", 1)), RoutingContext.initial())
            .get(0).getConfidence()).isEqualTo(0.70);
    }

    @Test
    void recoveryReasoningNamesTheFailedAgent() {
        RoutingContext ctx = RoutingContext.agentOffline("AGT-9", "Priya Sharma", List.of(order, order("ORD-2")));

        String reasoning = strategy.recommend(order, List.of(agent("AGT-1", 0)), ctx).get(0).getReasoning();

        assertThat(reasoning).startsWith("Recovery: Priya Sharma went offline leaving 2 stranded order(s).");
    }

    @Test
    void noCandidatesMeansNoRecommendation() {
        assertThat(strategy.recommend(order, List.of(), RoutingContext.initial())).isEmpty();
    }
}
