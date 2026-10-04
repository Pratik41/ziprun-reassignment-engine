package com.ziprun.exception;

/**
 * The recommended agent went OFFLINE while routing was in progress (routing reads
 * the roster, then may wait seconds for the LLM). Callers re-route instead of
 * persisting a suggestion nobody can act on.
 */
public class StaleRecommendationException extends InvalidStateException {
    public StaleRecommendationException(String agentId) {
        super("Recommended agent " + agentId + " went OFFLINE while routing was in progress");
    }
}
