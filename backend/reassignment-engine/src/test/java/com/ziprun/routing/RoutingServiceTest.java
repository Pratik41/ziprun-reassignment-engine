package com.ziprun.routing;

import com.ziprun.domain.AppSetting;
import com.ziprun.domain.Order;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.AppSettingRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.routing.strategy.RuleBasedStrategy;
import com.ziprun.service.activity.ActivityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ziprun.TestFixtures.agent;
import static com.ziprun.TestFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingServiceTest {

    private final AgentRepository agents = mock(AgentRepository.class);
    private final ReassignmentSuggestionRepository suggestions = mock(ReassignmentSuggestionRepository.class);
    private final AppSettingRepository settings = mock(AppSettingRepository.class);
    private final ActivityService activity = mock(ActivityService.class);
    private final RoutingStrategy confidentAi = new RoutingStrategy() {
        public List<RoutingResult> recommend(Order o, List<com.ziprun.domain.Agent> candidates, RoutingContext c) {
            return List.of(new RoutingResult(candidates.get(0).getId(), 0.95, "Clearly the best.", "ai:test"));
        }
        public String getName() { return "ai"; }
    };

    private RoutingService service(String configured) {
        return new RoutingService(Map.of("rule-based", new RuleBasedStrategy(), "ai", confidentAi),
            agents, suggestions, settings, activity, configured);
    }

    @Test
    void savedStrategyWinsOverConfigOnRestart() {
        when(settings.findById(AppSetting.ROUTING_STRATEGY)).thenReturn(Optional.of(new AppSetting(AppSetting.ROUTING_STRATEGY, "rule-based")));

        assertThat(service("ai").getActiveStrategyName()).isEqualTo("rule-based");
    }

    @Test
    void unknownSavedStrategyFallsBackToConfig() {
        when(settings.findById(AppSetting.ROUTING_STRATEGY)).thenReturn(Optional.of(new AppSetting(AppSetting.ROUTING_STRATEGY, "zone")));

        assertThat(service("ai").getActiveStrategyName()).isEqualTo("ai");
    }

    @Test
    void switchingSavesTheChoice() {
        when(settings.findById(any())).thenReturn(Optional.empty());

        service("ai").switchStrategy("rule-based");

        verify(settings).save(argThat(s -> s.getKey().equals(AppSetting.ROUTING_STRATEGY) && s.getValue().equals("rule-based")));
    }

    @Test
    void thinRosterCapsConfidenceAndSaysWhy() {
        when(settings.findById(any())).thenReturn(Optional.empty());
        when(agents.findByStatus(any())).thenReturn(List.of(agent("AGT-1", 0)));
        when(suggestions.countPendingByRecommendedAgent()).thenReturn(List.of());
        Order o = order("ORD-1");
        RoutingContext recovery = RoutingContext.agentOffline("AGT-9", "Priya", List.of(o, order("ORD-2"), order("ORD-3")));

        RoutingResult r = service("ai").route(o, recovery).orElseThrow();

        assertThat(r.getConfidence()).isEqualTo(RoutingService.THIN_ROSTER_CAP);
        assertThat(r.getReasoning()).contains("Thin roster: 3 stranded order(s) but only 1 available agent(s)");
        assertThat(r.getRoutingMillis()).isNotNull();
    }

    @Test
    void singleCandidateIsCappedEvenOnDemand() {
        when(settings.findById(any())).thenReturn(Optional.empty());
        when(agents.findByStatus(any())).thenReturn(List.of(agent("AGT-1", 0)));
        when(suggestions.countPendingByRecommendedAgent()).thenReturn(List.of());

        RoutingResult r = service("ai").route(order("ORD-1"), RoutingContext.initial()).orElseThrow();

        assertThat(r.getConfidence()).isEqualTo(RoutingService.SINGLE_CANDIDATE_CAP);
        assertThat(r.getReasoning()).isEqualTo("Clearly the best.");
    }

    @Test
    void agentsAtCapacityAreLeftOutAndNamed() {
        when(settings.findById(any())).thenReturn(Optional.empty());
        var full = agent("AGT-1", 3);
        full.setMaxCapacity(3);
        when(agents.findByStatus(any())).thenReturn(List.of(full, agent("AGT-2", 4), agent("AGT-3", 1)));
        when(suggestions.countPendingByRecommendedAgent()).thenReturn(List.of());
        RoutingService service = new RoutingService(Map.of("rule-based", new RuleBasedStrategy()),
            agents, suggestions, settings, activity, "rule-based", 6);

        List<RoutingResult> ranked = service.rank(order("ORD-1"), RoutingContext.initial());

        assertThat(ranked).extracting(RoutingResult::getRecommendedAgentId).containsExactly("AGT-3", "AGT-2");
        assertThat(ranked.get(0).getReasoning()).contains("Skipped (at capacity): Name AGT-1 3/3.");
    }

    @Test
    void everyoneFullStillSuggestsButCapsConfidenceAndWarns() {
        when(settings.findById(any())).thenReturn(Optional.empty());
        when(agents.findByStatus(any())).thenReturn(List.of(agent("AGT-1", 2), agent("AGT-2", 3)));
        when(suggestions.countPendingByRecommendedAgent()).thenReturn(List.of());
        RoutingService service = new RoutingService(Map.of("rule-based", new RuleBasedStrategy()),
            agents, suggestions, settings, activity, "rule-based", 2);

        RoutingResult r = service.route(order("ORD-1"), RoutingContext.initial()).orElseThrow();

        assertThat(r.getRecommendedAgentId()).isEqualTo("AGT-1");
        assertThat(r.getConfidence()).isEqualTo(RoutingService.OVER_CAPACITY_CAP);
        assertThat(r.getReasoning()).contains("Over capacity: every available agent is at their limit");
    }

    @Test
    void queuedSuggestionsCountTowardsCapacity() {
        when(settings.findById(any())).thenReturn(Optional.empty());
        when(agents.findByStatus(any())).thenReturn(List.of(agent("AGT-1", 1), agent("AGT-2", 2)));
        when(suggestions.countPendingByRecommendedAgent()).thenReturn(List.<Object[]>of(new Object[]{"AGT-1", 2L}));
        RoutingService service = new RoutingService(Map.of("rule-based", new RuleBasedStrategy()),
            agents, suggestions, settings, activity, "rule-based", 3);

        // AGT-1: 1 active + 2 queued = 3/3, full; AGT-2: 2/3
        assertThat(service.rank(order("ORD-1"), RoutingContext.initial()))
            .extracting(RoutingResult::getRecommendedAgentId).containsExactly("AGT-2");
    }

    @Test
    void enoughCandidatesLeavesConfidenceAlone() {
        when(settings.findById(any())).thenReturn(Optional.empty());
        when(agents.findByStatus(any())).thenReturn(List.of(agent("AGT-1", 0), agent("AGT-2", 1)));
        when(suggestions.countPendingByRecommendedAgent()).thenReturn(List.of());

        assertThat(service("ai").route(order("ORD-1"), RoutingContext.initial()).orElseThrow().getConfidence()).isEqualTo(0.95);
    }
}
