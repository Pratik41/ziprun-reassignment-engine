package com.ziprun.service.ai;

import java.util.List;

/**
 * Data Transfer Object for AI recommendation.
 * Immutable and thread-safe representation of what the LLM returned.
 *
 * Supports both:
 * - Single recommendation (backward compatible)
 * - Multiple recommendations (top 3 options for user to choose from)
 *
 * Separates AI response parsing from routing logic.
 */
public class AIRecommendation {
    private final String recommendedAgentId;
    private final Double confidence;
    private final String reasoning;
    private final List<AIRecommendationOption> topOptions;

    private AIRecommendation(String recommendedAgentId, Double confidence, String reasoning, List<AIRecommendationOption> topOptions) {
        this.recommendedAgentId = recommendedAgentId;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.topOptions = topOptions;
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

    public List<AIRecommendationOption> getTopOptions() {
        return topOptions;
    }

    public boolean hasMultipleOptions() {
        return topOptions != null && !topOptions.isEmpty();
    }

    public static AIRecommendation of(String agentId, Double confidence, String reasoning) {
        return new AIRecommendation(agentId, confidence, reasoning, null);
    }

    public static AIRecommendation ofMultiple(List<AIRecommendationOption> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        AIRecommendationOption first = options.get(0);
        return new AIRecommendation(first.getAgentId(), first.getConfidence(), first.getReasoning(), options);
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
