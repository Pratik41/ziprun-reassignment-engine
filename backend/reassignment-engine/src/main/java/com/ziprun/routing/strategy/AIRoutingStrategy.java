package com.ziprun.routing.strategy;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingResult;
import com.ziprun.routing.RoutingStrategy;
import com.ziprun.service.ai.AIAdvisorService;
import com.ziprun.service.ai.AIRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI-powered routing strategy.
 *
 * Delegates to AIAdvisorService for LLM integration. This keeps routing strategy
 * focused on the contract (recommend agent) while offloading AI complexity to
 * a dedicated service. Falls back to rule-based strategy on any AI failure.
 */
@Component("ai")
public class AIRoutingStrategy implements RoutingStrategy {
    private static final Logger log = LoggerFactory.getLogger(AIRoutingStrategy.class);

    private final AIAdvisorService aiAdvisor;
    private final RuleBasedStrategy fallbackStrategy;

    public AIRoutingStrategy(AIAdvisorService aiAdvisor, RuleBasedStrategy fallbackStrategy) {
        this.aiAdvisor = aiAdvisor;
        this.fallbackStrategy = fallbackStrategy;
    }

    @Override
    public RoutingResult recommend(Order order, List<Agent> availableAgents) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            return RoutingResult.of(null, 0.0, "No available agents to handle this order");
        }

        try {
            AIRecommendation aiRec = aiAdvisor.suggestAgent(order, availableAgents);

            if (aiRec == null || aiRec.getRecommendedAgentId() == null) {
                log.warn(
                    "AI returned null recommendation for order {}. Falling back to rule-based.",
                    order.getId()
                );
                return fallbackStrategy.recommend(order, availableAgents);
            }

            // Validate agent exists in available list
            boolean agentExists = availableAgents.stream()
                .anyMatch(a -> a.getId().equals(aiRec.getRecommendedAgentId()));

            if (!agentExists) {
                log.warn(
                    "AI hallucinated agent ID '{}' not in roster for order {}. Falling back.",
                    aiRec.getRecommendedAgentId(),
                    order.getId()
                );
                return fallbackStrategy.recommend(order, availableAgents);
            }

            // Validate confidence is in valid range
            if (aiRec.getConfidence() == null ||
                aiRec.getConfidence() < 0.0 ||
                aiRec.getConfidence() > 1.0) {
                log.warn(
                    "AI returned invalid confidence {} for order {}. Falling back.",
                    aiRec.getConfidence(),
                    order.getId()
                );
                return fallbackStrategy.recommend(order, availableAgents);
            }

            log.debug(
                "AI recommendation for order {}: agent={}, confidence={}",
                order.getId(),
                aiRec.getRecommendedAgentId(),
                aiRec.getConfidence()
            );

            return RoutingResult.of(
                aiRec.getRecommendedAgentId(),
                aiRec.getConfidence(),
                aiRec.getReasoning()
            );

        } catch (Exception e) {
            log.error("AI advisor failed for order {}. Falling back to rule-based strategy.", order.getId(), e);
            return fallbackStrategy.recommend(order, availableAgents);
        }
    }

    @Override
    public String getName() {
        return "ai";
    }
}
