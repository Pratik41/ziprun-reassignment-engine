package com.ziprun.routing.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.List;
import java.util.Map;

/**
 * LLM Gateway: Abstraction over HTTP communication with LLM providers.
 *
 * Supports: Gemini, Groq (OpenAI-compatible), Ollama
 *
 * Responsibilities:
 * - Handle provider-specific request/response formatting
 * - Manage API authentication
 * - Parse provider responses into uniform String
 * - Propagate errors to caller (AIAdvisorService handles fallback)
 *
 * Configuration via application.properties:
 *   llm.provider = gemini | groq | ollama
 *   llm.api-key = ${LLM_API_KEY}
 *   llm.model = model-name
 *   llm.base-url = provider-url
 */
@Component
public class LLMGateway {
    private static final Logger log = LoggerFactory.getLogger(LLMGateway.class);

    @Value("${llm.provider}")
    private String provider;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.base-url}")
    private String baseUrl;

    private final RestClient http = RestClient.create();

    /**
     * Call the LLM with a prompt.
     *
     * @param prompt the user prompt
     * @return the LLM's text response
     * @throws RestClientException if HTTP call fails
     * @throws RuntimeException if response parsing fails
     */
    public String callLLM(String prompt) {
        log.debug("Calling LLM via provider: {}", provider);

        return switch (provider.toLowerCase()) {
            case "gemini" -> callGemini(prompt);
            case "groq" -> callOpenAICompatible(prompt, baseUrl + "/openai/v1/chat/completions");
            case "ollama" -> callOpenAICompatible(prompt, baseUrl + "/v1/chat/completions");
            default -> throw new IllegalStateException("Unknown LLM provider: " + provider);
        };
    }

    /**
     * Call Google Gemini API.
     */
    private String callGemini(String prompt) {
        String url = baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        Map<String, Object> body = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(
                    Map.of("text", prompt)
                ))
            )
        );

        try {
            Map<?, ?> response = http.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(Map.class);

            if (response == null) {
                throw new RuntimeException("Gemini returned null response");
            }

            @SuppressWarnings("unchecked")
            List<Object> candidates = (List<Object>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new RuntimeException("Gemini response missing candidates");
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> content = (Map<Object, Object>) candidates.get(0);
            if (content == null) {
                throw new RuntimeException("Gemini candidate missing content");
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> contentMap = (Map<Object, Object>) content.get("content");
            if (contentMap == null) {
                throw new RuntimeException("Gemini content missing content map");
            }

            @SuppressWarnings("unchecked")
            List<Object> parts = (List<Object>) contentMap.get("parts");
            if (parts == null || parts.isEmpty()) {
                throw new RuntimeException("Gemini response missing parts");
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> part = (Map<Object, Object>) parts.get(0);
            Object text = part.get("text");

            if (text == null) {
                throw new RuntimeException("Gemini response missing text");
            }

            return text.toString();

        } catch (Exception e) {
            log.error("Gemini API call failed", e);
            throw new RuntimeException("Gemini API error: " + e.getMessage(), e);
        }
    }

    /**
     * Call OpenAI-compatible API (Groq or Ollama).
     */
    private String callOpenAICompatible(String prompt, String url) {
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(
                Map.of(
                    "role", "user",
                    "content", prompt
                )
            )
        );

        try {
            Map<?, ?> response = http.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(Map.class);

            if (response == null) {
                throw new RuntimeException("LLM returned null response");
            }

            @SuppressWarnings("unchecked")
            List<Object> choices = (List<Object>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("LLM response missing choices");
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> choice = (Map<Object, Object>) choices.get(0);
            if (choice == null) {
                throw new RuntimeException("LLM choice is null");
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> message = (Map<Object, Object>) choice.get("message");
            if (message == null) {
                throw new RuntimeException("LLM choice missing message");
            }

            Object content = message.get("content");
            if (content == null) {
                throw new RuntimeException("LLM message missing content");
            }

            return content.toString();

        } catch (Exception e) {
            log.error("OpenAI-compatible API call failed", e);
            throw new RuntimeException("LLM API error: " + e.getMessage(), e);
        }
    }
}
