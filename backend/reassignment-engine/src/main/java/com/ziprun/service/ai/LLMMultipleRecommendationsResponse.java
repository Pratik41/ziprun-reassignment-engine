package com.ziprun.service.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * LLM response with multiple agent recommendations (top 3).
 * Used for JSON deserialization from LLM output when asking for multiple options.
 *
 * Format:
 * {
 *   "recommendations": [
 *     {"agent_id": "AGT-X", "confidence": 0.92, "reasoning": "..."},
 *     {"agent_id": "AGT-Y", "confidence": 0.85, "reasoning": "..."},
 *     {"agent_id": "AGT-Z", "confidence": 0.78, "reasoning": "..."}
 *   ]
 * }
 */
public class LLMMultipleRecommendationsResponse {
    @JsonProperty("recommendations")
    private List<LLMRecommendation> recommendations;

    public LLMMultipleRecommendationsResponse() {
    }

    public List<LLMRecommendation> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<LLMRecommendation> recommendations) {
        this.recommendations = recommendations;
    }

    public static class LLMRecommendation {
        @JsonProperty("agent_id")
        private String agentId;

        @JsonProperty("confidence")
        private Double confidence;

        @JsonProperty("reasoning")
        private String reasoning;

        public LLMRecommendation() {
        }

        public LLMRecommendation(String agentId, Double confidence, String reasoning) {
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
            return "Recommendation{" +
                "agent_id='" + agentId + '\'' +
                ", confidence=" + confidence +
                '}';
        }
    }
}
