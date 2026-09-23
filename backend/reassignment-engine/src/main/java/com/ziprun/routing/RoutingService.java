package com.ziprun.routing;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.Order;
import com.ziprun.repository.AgentRepository;
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

    @Value("${routing.strategy:rule-based}")
    private String activeStrategyName;

    public RoutingService(Map<String, RoutingStrategy> strategies, AgentRepository agentRepository) {
        this.strategies = strategies;
        this.agentRepository = agentRepository;
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
