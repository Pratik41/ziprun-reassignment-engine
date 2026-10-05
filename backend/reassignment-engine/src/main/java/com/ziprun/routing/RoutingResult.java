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
    /** Wall-clock time the routing call took (including any AI calls); set by RoutingService. */
    private final Long routingMillis;

    public RoutingResult(String recommendedAgentId, Double confidence, String reasoning, String source) {
        this(recommendedAgentId, confidence, reasoning, source, null);
    }

    public RoutingResult(String recommendedAgentId, Double confidence, String reasoning, String source, Long routingMillis) {
        this.recommendedAgentId = recommendedAgentId;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.source = source;
        this.routingMillis = routingMillis;
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

    public Long getRoutingMillis() {
        return routingMillis;
    }

    public RoutingResult withSource(String newSource) {
        return new RoutingResult(recommendedAgentId, confidence, reasoning, newSource, routingMillis);
    }

    public RoutingResult withRoutingMillis(long millis) {
        return new RoutingResult(recommendedAgentId, confidence, reasoning, source, millis);
    }

    /** Lowers confidence to at most {@code cap} and appends {@code note} to the reasoning. */
    public RoutingResult capped(double cap, String note) {
        return new RoutingResult(recommendedAgentId, Math.min(confidence, cap), reasoning + note, source, routingMillis);
    }

    @Override
    public String toString() {
        return "RoutingResult{agent=" + recommendedAgentId + ", confidence=" + confidence + ", source=" + source + '}';
    }
}
