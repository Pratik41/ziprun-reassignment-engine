package com.ziprun.routing.strategy;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingStrategy;
import com.ziprun.routing.gateway.LLMException;
import com.ziprun.service.ai.AIAdvisorService;
import com.ziprun.service.ai.AIRecommendation;
import com.ziprun.service.ai.AIRecommendationOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI-powered routing strategy.
 *
 * Delegates "what does the model say?" to AIAdvisorService, then owns
 * "can we trust it?":
 *  - every recommended agent_id must be in the roster we sent (no hallucinations)
 *  - confidence must be within [0, 1]; reasoning must be non-blank
 *  - duplicates are dropped; invalid options are skipped, valid ones kept
 *
 * Any failure (LLM chain exhausted, unparseable reply, nothing valid left)
 * falls back to the rule-based strategy with the same context, and the
 * fallback's source names the failure kind, e.g. "rule-based (AI fallback: TIMEOUT)".
 * This applies identically to the HTTP path and the async re-plan path.
 */
@Component(AIRoutingStrategy.NAME)
public class AIRoutingStrategy implements RoutingStrategy {
    public static final String NAME = "ai";
    private static final Logger log = LoggerFactory.getLogger(AIRoutingStrategy.class);

    private final AIAdvisorService aiAdvisor;
    private final RuleBasedStrategy fallbackStrategy;

    public AIRoutingStrategy(AIAdvisorService aiAdvisor, RuleBasedStrategy fallbackStrategy) {
        this.aiAdvisor = aiAdvisor;
        this.fallbackStrategy = fallbackStrategy;
    }

    @Override
    public List<RoutingResult> recommend(Order order, List<Agent> availableAgents, RoutingContext context) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            return List.of();
        }

        try {
            AIRecommendation advice = aiAdvisor.advise(order, availableAgents, context);
            return validate(order, advice, availableAgents);
        } catch (LLMException e) {
            return fallback(order, availableAgents, context, e.getKind().name(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected AI routing error for order {}", order.getId(), e);
            return fallback(order, availableAgents, context, "UNEXPECTED", e.toString());
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    private List<RoutingResult> validate(Order order, AIRecommendation advice, List<Agent> roster) {
        Set<String> rosterIds = roster.stream().map(Agent::getId).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        List<RoutingResult> valid = new ArrayList<>();
        int hallucinated = 0;

        for (AIRecommendationOption option : advice.options()) {
            if (option.agentId() == null || !rosterIds.contains(option.agentId())) {
                hallucinated++;
                log.warn("AI recommended agent '{}' which is not in the roster {} (order {}). Discarding option.",
                    option.agentId(), rosterIds, order.getId());
                continue;
            }
            if (option.confidence() == null || option.confidence() < 0.0 || option.confidence() > 1.0) {
                log.warn("AI returned invalid confidence {} for agent {} (order {}). Discarding option.",
                    option.confidence(), option.agentId(), order.getId());
                continue;
            }
            if (option.reasoning() == null || option.reasoning().isBlank()) {
                log.warn("AI returned blank reasoning for agent {} (order {}). Discarding option.",
                    option.agentId(), order.getId());
                continue;
            }
            if (seen.add(option.agentId())) {
                valid.add(new RoutingResult(option.agentId(), option.confidence(), option.reasoning(),
                    NAME + ":" + advice.provider()));
            }
        }

        if (valid.isEmpty()) {
            LLMException.Kind kind = hallucinated == advice.options().size()
                ? LLMException.Kind.HALLUCINATED_AGENT
                : LLMException.Kind.INVALID_RESPONSE;
            throw new LLMException(kind, "No usable recommendation in AI reply from " + advice.provider());
        }
        return valid;
    }

    private List<RoutingResult> fallback(Order order, List<Agent> agents, RoutingContext context,
                                         String failureKind, String detail) {
        log.warn("AI routing failed for order {} [{}] ({}): {}. Falling back to rule-based.",
            order.getId(), failureKind, context.trigger(), detail);
        context.listener().restart("AI unavailable (" + failureKind + "), using rule-based routing");
        String source = RuleBasedStrategy.NAME + " (AI fallback: " + failureKind + ")";
        return fallbackStrategy.recommend(order, agents, context).stream()
            .map(result -> result.withSource(source))
            .toList();
    }
}
