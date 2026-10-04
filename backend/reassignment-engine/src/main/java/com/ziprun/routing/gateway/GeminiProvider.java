package com.ziprun.routing.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Google Gemini via the generateContent REST API.
 * Request/response handling with explicit timeouts and typed errors.
 *
 * Config: llm.gemini.api-key, llm.gemini.model, llm.gemini.base-url
 */
@Component
public class GeminiProvider implements LLMProvider {

    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public GeminiProvider(
            RestClient llmRestClient,
            ObjectMapper objectMapper,
            @Value("${llm.gemini.api-key:}") String apiKey,
            @Value("${llm.gemini.model:gemini-2.5-flash}") String model,
            @Value("${llm.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl
    ) {
        this.http = llmRestClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    @Override
    public String getName() {
        return "gemini";
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String streamLLM(String prompt, Consumer<String> onChunk) {
        var request = http.post()
            .uri(baseUrl + "/models/" + model + ":streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody(prompt));
        return SseStreams.read(request, getName(), objectMapper,
            event -> SseStreams.path(event, "candidates", 0, "content", "parts", 0, "text").asText(""),
            onChunk);
    }

    private static Map<String, Object> requestBody(String prompt) {
        return Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig", Map.of("temperature", 0.2)
        );
    }

    @Override
    public String callLLM(String prompt) {
        String url = baseUrl + "/models/" + model + ":generateContent";
        Map<String, Object> body = requestBody(prompt);

        Map<?, ?> response;
        try {
            response = http.post()
                .uri(url)
                .header("x-goog-api-key", apiKey)   // header, so the key never appears in URLs or logs
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        } catch (RestClientException e) {
            throw LLMException.fromHttp(getName(), e);
        }

        try {
            var candidates = (List<?>) response.get("candidates");
            var content = (Map<?, ?>) ((Map<?, ?>) candidates.get(0)).get("content");
            var parts = (List<?>) content.get("parts");
            String text = (String) ((Map<?, ?>) parts.get(0)).get("text");
            if (text == null || text.isBlank()) {
                throw new LLMException(LLMException.Kind.EMPTY_RESPONSE, "gemini returned empty text");
            }
            return text;
        } catch (LLMException e) {
            throw e;
        } catch (RuntimeException e) {
            // Missing candidates usually means the prompt was blocked or the shape changed
            throw new LLMException(LLMException.Kind.EMPTY_RESPONSE, "gemini response had no candidate text", e);
        }
    }
}
