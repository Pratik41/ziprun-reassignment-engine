package com.ziprun.routing.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Any backend that speaks the OpenAI chat/completions format (Groq, Ollama).
 * Instances are declared in LLMConfig, one per backend.
 */
public class OpenAICompatibleProvider implements LLMProvider {

    private final String name;
    private final RestClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final boolean requiresApiKey;

    public OpenAICompatibleProvider(String name, RestClient http, ObjectMapper objectMapper, String baseUrl,
                                    String model, String apiKey, boolean requiresApiKey) {
        this.name = name;
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.requiresApiKey = requiresApiKey;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isConfigured() {
        boolean hasUrl = baseUrl != null && !baseUrl.isBlank();
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        return hasUrl && (hasKey || !requiresApiKey);
    }

    @Override
    public String streamLLM(String prompt, Consumer<String> onChunk) {
        Map<String, Object> body = new HashMap<>(requestBody(prompt));
        body.put("stream", true);
        return SseStreams.read(newRequest().body(body), name, objectMapper,
            event -> SseStreams.path(event, "choices", 0, "delta", "content").asText(""),
            onChunk);
    }

    private Map<String, Object> requestBody(String prompt) {
        return Map.of(
            "model", model,
            "temperature", 0.2,
            "messages", List.of(Map.of("role", "user", "content", prompt))
        );
    }

    private RestClient.RequestBodySpec newRequest() {
        RestClient.RequestBodySpec request = http.post()
            .uri(baseUrl + "/chat/completions")
            .contentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            request = request.header("Authorization", "Bearer " + apiKey);
        }
        return request;
    }

    @Override
    public String callLLM(String prompt) {
        Map<?, ?> response;
        try {
            response = newRequest().body(requestBody(prompt)).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw LLMException.fromHttp(name, e);
        }

        try {
            var choices = (List<?>) response.get("choices");
            var message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
            String text = (String) message.get("content");
            if (text == null || text.isBlank()) {
                throw new LLMException(LLMException.Kind.EMPTY_RESPONSE, name + " returned empty content");
            }
            return text;
        } catch (LLMException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LLMException(LLMException.Kind.EMPTY_RESPONSE, name + " response had no message content", e);
        }
    }
}
