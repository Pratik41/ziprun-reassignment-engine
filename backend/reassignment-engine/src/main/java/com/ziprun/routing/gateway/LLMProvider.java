package com.ziprun.routing.gateway;

/**
 * LLM Provider interface: one implementation per backend's wire format.
 *
 * Implementations:
 * - GeminiProvider: Google Gemini (generateContent API)
 * - OpenAICompatibleProvider: Groq and Ollama (chat/completions API)
 * - MockLLMProvider: local, deterministic, no network (with failure injection)
 *
 * LLMGateway chains providers in the order given by llm.providers.
 */
public interface LLMProvider {
    /** Name used in llm.providers, e.g. "gemini". */
    String getName();

    /** False when required config (API key, URL) is missing; the gateway skips it. */
    boolean isConfigured();

    /**
     * Sends the prompt and returns the model's raw text.
     *
     * @throws LLMException classified failure (timeout, rate limit, HTTP error, empty)
     */
    String callLLM(String prompt);
}
