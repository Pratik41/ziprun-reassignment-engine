package com.ziprun.service.ai;

public class AIRecommendationOption {
    private String agentId;
    private Double confidence;
    private String reasoning;

    public AIRecommendationOption(String agentId, Double confidence, String reasoning) {
        this.agentId = agentId;
        this.confidence = confidence;
        this.reasoning = reasoning;
    }

    public String getAgentId() {
        return agentId;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getReasoning() {
        return reasoning;
    }

    @Override
    public String toString() {
        return "Option{" +
            "agent=" + agentId +
            ", confidence=" + confidence +
            ", reasoning='" + reasoning + '\'' +
            '}';
    }
}
