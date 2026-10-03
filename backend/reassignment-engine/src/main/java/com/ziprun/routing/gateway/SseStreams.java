package com.ziprun.routing.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reads a provider's Server-Sent Events response line by line.
 * Each "data:" payload is parsed as JSON and mapped to a text fragment.
 */
final class SseStreams {

    private SseStreams() {
    }

    /**
     * @param request   prepared POST (uri, headers, body)
     * @param provider  name used in error messages
     * @param toText    extracts the text fragment from one JSON event (null/empty = nothing to emit)
     * @param onChunk   receives each fragment
     * @return the concatenated text
     */
    static String read(RestClient.RequestBodySpec request, String provider, ObjectMapper json,
                       Function<JsonNode, String> toText, Consumer<String> onChunk) {
        StringBuilder full = new StringBuilder();
        try {
            request.exchange((req, resp) -> {
                if (resp.getStatusCode().isError()) {
                    throw LLMException.fromStatus(provider, resp.getStatusCode().value());
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String payload = line.substring(5).trim();
                        if (payload.isEmpty() || payload.equals("[DONE]")) {
                            continue;
                        }
                        JsonNode event;
                        try {
                            event = json.readTree(payload);
                        } catch (IOException malformed) {
                            continue; // skip a broken event rather than abort the stream
                        }
                        String text = toText.apply(event);
                        if (text != null && !text.isEmpty()) {
                            full.append(text);
                            onChunk.accept(text);
                        }
                    }
                }
                return null;
            });
        } catch (RestClientException e) {
            throw LLMException.fromHttp(provider, e);
        } catch (UncheckedIOException e) {
            throw new LLMException(LLMException.Kind.HTTP_ERROR, provider + " stream broke: " + e.getMessage(), e);
        }
        if (full.isEmpty()) {
            throw new LLMException(LLMException.Kind.EMPTY_RESPONSE, provider + " streamed no text");
        }
        return full.toString();
    }

    static JsonNode path(JsonNode node, Object... steps) {
        JsonNode current = node;
        for (Object step : steps) {
            current = step instanceof Integer i ? current.path(i) : current.path((String) step);
        }
        return current;
    }
}
