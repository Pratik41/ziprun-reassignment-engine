package com.ziprun.service.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured LLM response for agent recommendations.
 * Used for JSON deserialization from LLM output.
 *
 * The LLM is instructed to return JSON in this exact shape.
 */
public class LLMResponse {
    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("confidence")
    private Double confidence;

    @JsonProperty("reasoning")
    private String reasoning;

    // Constructors
    public LLMResponse() {
    }

    public LLMResponse(String agentId, Double confidence, String reasoning) {
        this.agentId = agentId;
        this.confidence = confidence;
        this.reasoning = reasoning;
    }

    // Getters and Setters
    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
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

    @Override
    public String toString() {
        return "LLMResponse{" +
               "agentId='" + agentId + '\'' +
               ", confidence=" + confidence +
               ", reasoning='" + (reasoning != null ? reasoning.substring(0, Math.min(50, reasoning.length())) + "..." : "null") + '\'' +
               '}';
    }
}
