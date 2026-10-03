package com.ziprun.routing.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wiring for LLM HTTP calls.
 *
 * The shared RestClient has explicit connect/read timeouts; without them a hung
 * provider would hold an async re-plan thread indefinitely.
 */
@Configuration
public class LLMConfig {

    @Bean
    public RestClient llmRestClient(@Value("${llm.timeout-ms:8000}") long timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(timeoutMs, 3000)));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    public LLMProvider groqProvider(
            RestClient llmRestClient,
            @Value("${llm.groq.api-key:}") String apiKey,
            @Value("${llm.groq.model:llama-3.1-8b-instant}") String model,
            @Value("${llm.groq.base-url:https://api.groq.com/openai/v1}") String baseUrl
    ) {
        return new OpenAICompatibleProvider("groq", llmRestClient, baseUrl, model, apiKey, true);
    }

    @Bean
    public LLMProvider ollamaProvider(
            RestClient llmRestClient,
            @Value("${llm.ollama.model:llama3.1}") String model,
            @Value("${llm.ollama.base-url:http://localhost:11434/v1}") String baseUrl
    ) {
        return new OpenAICompatibleProvider("ollama", llmRestClient, baseUrl, model, null, false);
    }
}
