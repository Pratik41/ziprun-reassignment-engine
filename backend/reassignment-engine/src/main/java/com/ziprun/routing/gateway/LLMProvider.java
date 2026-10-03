package com.ziprun.routing.gateway;

/**
 * LLM Provider interface: one implementation per backend's wire format.
 *
 * Implementations:
 * - GeminiProvider: Google Gemini (generateContent API)
 * - OpenAICompatibleProvider: Groq and Ollama (chat/completions API)
 * (Tests add a deterministic fake provider named "mock"; it is not part of the application.)
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

    /**
     * Streaming variant: pushes text chunks to onChunk as they arrive and
     * returns the full text. Providers without native streaming deliver the
     * whole reply as one chunk.
     *
     * @throws LLMException same classification as callLLM
     */
    default String streamLLM(String prompt, java.util.function.Consumer<String> onChunk) {
        String text = callLLM(prompt);
        onChunk.accept(text);
        return text;
    }
}
