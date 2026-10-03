package com.ziprun.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.gateway.LLMException;
import com.ziprun.routing.gateway.LLMGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.ziprun.TestFixtures.agent;
import static com.ziprun.TestFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AIAdvisorServiceTest {

    private final LLMGateway gateway = mock(LLMGateway.class);
    private final AIAdvisorService advisor = new AIAdvisorService(gateway, new ObjectMapper());

    @Test
    void parsesRankedListInsideMarkdownFences() {
        List<AIRecommendationOption> options = advisor.parse("""
            ```json
            {"recommendations":[{"agent_id":"AGT-1","confidence":0.9,"reasoning":"a"},
                                {"agent_id":"AGT-2","confidence":0.7,"reasoning":"b"}]}
            ```""");

        assertThat(options).extracting(AIRecommendationOption::agentId).containsExactly("AGT-1", "AGT-2");
    }

    @Test
    void parsesBriefsSingleObjectFormatWithProse() {
        List<AIRecommendationOption> options =
            advisor.parse("Here you go: {\"agentId\":\"AGT-3\",\"confidence\":0.85,\"reasoning\":\"r\"} hope it helps");

        assertThat(options).containsExactly(new AIRecommendationOption("AGT-3", 0.85, "r"));
    }

    @Test
    void proseIsUnparseable() {
        assertThatThrownBy(() -> advisor.parse("I think Rahul."))
            .isInstanceOfSatisfying(LLMException.class, e -> assertThat(e.getKind()).isEqualTo(LLMException.Kind.UNPARSEABLE));
    }

    @Test
    void jsonWithoutAgentIdIsUnparseable() {
        assertThatThrownBy(() -> advisor.parse("{\"answer\":\"AGT-1\"}"))
            .isInstanceOfSatisfying(LLMException.class, e -> assertThat(e.getKind()).isEqualTo(LLMException.Kind.UNPARSEABLE));
    }

    @Test
    void recoveryContextUsesTheReplanPrompt() {
        when(gateway.callLLM(anyString())).thenReturn(new LLMGateway.LLMReply("mock",
            "{\"agent_id\":\"AGT-1\",\"confidence\":0.9,\"reasoning\":\"r\"}"));
        Order o = order("ORD-1");

        advisor.advise(o, List.of(agent("AGT-1", 0)), RoutingContext.agentOffline("AGT-9", "Priya Sharma", List.of(o)));

        verify(gateway).callLLM(contains("INCIDENT REPORT"));
    }

    @Test
    void promptsAreGenuinelyDifferent() {
        Order o = order("ORD-1");
        List<com.ziprun.domain.Agent> roster = List.of(agent("AGT-1", 0));
        String initial = PromptBuilder.buildInitialAssignmentPrompt(o, roster, RoutingContext.initial());
        String replan = PromptBuilder.buildReplanPrompt(o, roster,
            RoutingContext.agentOffline("AGT-9", "Priya Sharma", List.of(o, order("ORD-2"))));

        assertThat(initial).contains("routine assignment").doesNotContain("RECOVERY");
        assertThat(replan).contains("Priya Sharma (AGT-9) has gone OFFLINE", "2 order(s) are stranded",
            "ORD-1 - Parcel ORD-1   <- THIS ORDER", "ORD-2 - Parcel ORD-2", "Spread the batch");
    }
}
