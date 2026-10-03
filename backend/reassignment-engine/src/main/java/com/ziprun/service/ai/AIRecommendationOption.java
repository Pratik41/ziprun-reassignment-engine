package com.ziprun.service.ai;

/**
 * One recommendation as the model returned it: { agent_id, confidence, reasoning }.
 */
public record AIRecommendationOption(String agentId, Double confidence, String reasoning) {
}
