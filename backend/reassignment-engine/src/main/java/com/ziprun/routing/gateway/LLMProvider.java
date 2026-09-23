package com.ziprun.routing.gateway;

/**
 * LLM Provider interface: Abstraction for different LLM backends.
 *
 * Implementations:
 * - LLMGateway: Real LLM (Gemini/Groq/Ollama)
 * - MockLLMGateway: Local mock (no API key needed)
 */
public interface LLMProvider {
    String callLLM(String prompt);
    String getName();
}
