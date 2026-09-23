package com.ziprun.routing;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.Order;
import com.ziprun.repository.AgentRepository;
import com.ziprun.service.ai.AIAdvisorService;
import com.ziprun.service.ai.AIRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/**
 * Routing Service: orchestrates strategy selection and execution.
 *
 * Responsibilities:
 * - Query available agents from repository
 * - Select active routing strategy from bean map
 * - Execute strategy on order + agents
 * - Support both normal routing and re-plan routing (with recovery context)
 * - Log routing decisions at DEBUG level
 *
 * Design: This service doesn't know about AI or rules; it knows about
 * strategies. Makes it easy to add new strategies without modifying this class.
 */
@Service
public class RoutingService {
    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final Map<String, RoutingStrategy> strategies;
    private final AgentRepository agentRepository;
    private final AIAdvisorService aiAdvisor;

    @Value("${routing.strategy:rule-based}")
    private String activeStrategyName;

    public RoutingService(Map<String, RoutingStrategy> strategies, AgentRepository agentRepository, AIAdvisorService aiAdvisor) {
        this.strategies = strategies;
        this.agentRepository = agentRepository;
        this.aiAdvisor = aiAdvisor;
    }

    public RoutingResult route(Order order) {
        List<Agent> availableAgents = agentRepository.findByStatus(AgentStatus.AVAILABLE);

        RoutingStrategy strategy = getActiveStrategy();
        RoutingResult result = strategy.recommend(order, availableAgents);

        log.debug(
            "Routing suggestion for order {}: agent={}, confidence={}, strategy={}",
            order.getId(),
            result.getRecommendedAgentId(),
            result.getConfidence(),
            strategy.getName()
        );

        return result;
    }

    /**
     * Route with recovery context: used when agent goes offline.
     * If strategy is AI, use the re-plan prompt (recovery mode).
     * If strategy is rule-based, use normal routing.
     */
    public RoutingResult routeWithRecoveryContext(Order order, String failedAgentId, String failedAgentName, int strandedOrderCount) {
        List<Agent> availableAgents = agentRepository.findByStatus(AgentStatus.AVAILABLE);
        String strategyName = getActiveStrategyName();

        RoutingResult result;

        if ("ai".equals(strategyName)) {
            AIRecommendation aiRec = aiAdvisor.suggestReassignment(
                order,
                availableAgents,
                failedAgentId,
                failedAgentName,
                strandedOrderCount
            );

            if (aiRec != null && aiRec.getRecommendedAgentId() != null) {
                result = RoutingResult.of(
                    aiRec.getRecommendedAgentId(),
                    aiRec.getConfidence(),
                    aiRec.getReasoning()
                );
            } else {
                log.warn("AI re-plan failed for order {}. Falling back to rule-based.", order.getId());
                result = getActiveStrategy().recommend(order, availableAgents);
            }
        } else {
            result = getActiveStrategy().recommend(order, availableAgents);
        }

        log.debug(
            "Re-plan routing for order {} (failed agent: {}): agent={}, confidence={}, strategy={}",
            order.getId(),
            failedAgentId,
            result.getRecommendedAgentId(),
            result.getConfidence(),
            strategyName
        );

        return result;
    }

    private RoutingStrategy getActiveStrategy() {
        RoutingStrategy strategy = strategies.get(activeStrategyName);

        if (strategy == null) {
            log.warn(
                "Configured strategy '{}' not found. Available: {}. Falling back to rule-based.",
                activeStrategyName,
                strategies.keySet()
            );
            strategy = strategies.get("rule-based");
        }

        return strategy;
    }

    public String getActiveStrategyName() {
        return activeStrategyName;
    }
}
