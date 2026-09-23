package com.ziprun.service.ai;

/**
 * Data Transfer Object for AI recommendation.
 * Immutable and thread-safe representation of what the LLM returned.
 *
 * Separates AI response parsing from routing logic.
 */
public class AIRecommendation {
    private final String recommendedAgentId;
    private final Double confidence;
    private final String reasoning;

    private AIRecommendation(String recommendedAgentId, Double confidence, String reasoning) {
        this.recommendedAgentId = recommendedAgentId;
        this.confidence = confidence;
        this.reasoning = reasoning;
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

    public static AIRecommendation of(String agentId, Double confidence, String reasoning) {
        return new AIRecommendation(agentId, confidence, reasoning);
    }

    @Override
    public String toString() {
        return "AIRecommendation{" +
               "agentId='" + recommendedAgentId + '\'' +
               ", confidence=" + confidence +
               ", reasoning='" + (reasoning != null ? reasoning.substring(0, Math.min(50, reasoning.length())) + "..." : "null") + '\'' +
               '}';
    }
}
