package com.ziprun.service.ai;

import java.util.List;

/**
 * What the LLM said, already parsed but NOT yet validated against the roster.
 * Validation (agent exists, confidence in range) is AIRoutingStrategy's job.
 *
 * @param provider which LLM in the chain answered (e.g. "gemini", "groq")
 * @param options  ranked recommendations as returned by the model, best first
 */
public record AIRecommendation(String provider, List<AIRecommendationOption> options) {
}
