package com.ziprun.routing.strategy;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.gateway.LLMException;
import com.ziprun.service.ai.AIAdvisorService;
import com.ziprun.service.ai.AIRecommendation;
import com.ziprun.service.ai.AIRecommendationOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.ziprun.TestFixtures.agent;
import static com.ziprun.TestFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIRoutingStrategyTest {

    private final AIAdvisorService advisor = mock(AIAdvisorService.class);
    private final AIRoutingStrategy strategy = new AIRoutingStrategy(advisor, new RuleBasedStrategy());
    private final Order order = order("ORD-1");
    private final List<Agent> roster = List.of(agent("AGT-1", 2), agent("AGT-2", 0));

    @Test
    void validAdviceIsReturnedWithProvider() {
        advise(new AIRecommendationOption("AGT-1", 0.9, "Because reasons"));

        List<RoutingResult> results = strategy.recommend(order, roster, RoutingContext.initial());

        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.getRecommendedAgentId()).isEqualTo("AGT-1");
            assertThat(r.getSource()).isEqualTo("ai:gemini");
            assertThat(r.getReasoning()).isEqualTo("Because reasons");
        });
    }

    @Test
    void invalidOptionsAreDroppedButValidOnesKept() {
        advise(new AIRecommendationOption("AGT-404", 0.95, "made up"),
               new AIRecommendationOption("AGT-1", 1.7, "bad confidence"),
               new AIRecommendationOption("AGT-2", 0.8, "fine"));

        assertThat(strategy.recommend(order, roster, RoutingContext.initial()))
            .extracting(RoutingResult::getRecommendedAgentId).containsExactly("AGT-2");
    }

    @Test
    void hallucinatedAgentFallsBackToRuleBased() {
        advise(new AIRecommendationOption("AGT-404", 0.95, "made up"));

        RoutingResult top = strategy.recommend(order, roster, RoutingContext.initial()).get(0);

        assertThat(top.getRecommendedAgentId()).isEqualTo("AGT-2");
        assertThat(top.getSource()).isEqualTo("rule-based (AI fallback: HALLUCINATED_AGENT)");
    }

    @Test
    void llmFailureFallsBackToRuleBasedOnRecoveryPathToo() {
        when(advisor.advise(any(), any(), any())).thenThrow(new LLMException(LLMException.Kind.TIMEOUT, "slow"));
        RoutingContext recovery = RoutingContext.agentOffline("AGT-9", "Priya", List.of(order));

        RoutingResult top = strategy.recommend(order, roster, recovery).get(0);

        assertThat(top.getRecommendedAgentId()).isEqualTo("AGT-2");
        assertThat(top.getSource()).isEqualTo("rule-based (AI fallback: TIMEOUT)");
        assertThat(top.getReasoning()).startsWith("Recovery:");
    }

    @Test
    void unexpectedErrorStillFallsBack() {
        when(advisor.advise(any(), any(), any())).thenThrow(new IllegalStateException("bug"));

        assertThat(strategy.recommend(order, roster, RoutingContext.initial()).get(0).getSource())
            .isEqualTo("rule-based (AI fallback: UNEXPECTED)");
    }

    private void advise(AIRecommendationOption... options) {
        when(advisor.advise(any(), any(), any())).thenReturn(new AIRecommendation("gemini", List.of(options)));
    }
}
