package com.ziprun.routing;

/**
 * One ranked recommendation from a routing strategy.
 *
 * source records which strategy really produced it ("ai:gemini", "rule-based",
 * "rule-based (AI fallback: TIMEOUT)") so a fallback is visible to ops rather
 * than silently masquerading as an AI answer.
 */
public class RoutingResult {
    private final String recommendedAgentId;
    private final Double confidence;
    private final String reasoning;
    private final String source;

    public RoutingResult(String recommendedAgentId, Double confidence, String reasoning, String source) {
        this.recommendedAgentId = recommendedAgentId;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.source = source;
    }

    public String getRecommendedAgentId() {
        return recommendedAgentId;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getReasoning() {
        return reasoning;
    }

    public String getSource() {
        return source;
    }

    public RoutingResult withSource(String newSource) {
        return new RoutingResult(recommendedAgentId, confidence, reasoning, newSource);
    }

    @Override
    public String toString() {
        return "RoutingResult{agent=" + recommendedAgentId + ", confidence=" + confidence + ", source=" + source + '}';
    }
}
