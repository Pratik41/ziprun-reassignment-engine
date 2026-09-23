package com.ziprun.routing;

public class RoutingResult {
    private String recommendedAgentId;
    private Double confidence;
    private String reasoning;

    public RoutingResult() {
    }

    public RoutingResult(String recommendedAgentId, Double confidence, String reasoning) {
        this.recommendedAgentId = recommendedAgentId;
        this.confidence = confidence;
        this.reasoning = reasoning;
    }

    public String getRecommendedAgentId() {
        return recommendedAgentId;
    }

    public void setRecommendedAgentId(String recommendedAgentId) {
        this.recommendedAgentId = recommendedAgentId;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public static RoutingResult of(String agentId, Double confidence, String reasoning) {
        return new RoutingResult(agentId, confidence, reasoning);
    }
}
