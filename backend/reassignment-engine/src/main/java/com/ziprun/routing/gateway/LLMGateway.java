package com.ziprun.routing.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM Gateway: calls providers in the order configured by llm.providers
 * (e.g. "gemini,groq") and returns the first successful reply.
 *
 * Transport failures (timeout, 429, HTTP error, empty reply, missing key) move
 * on to the next provider. If every provider fails, the last LLMException is
 * thrown and the AI strategy falls back to rule-based routing.
 *
 * Content problems (bad JSON, hallucinated agent) are NOT retried on another
 * provider: they surface to the AI strategy, which validates and falls back.
 */
@Component
public class LLMGateway {
    private static final Logger log = LoggerFactory.getLogger(LLMGateway.class);

    /** Raw model text plus which provider produced it. */
    public record LLMReply(String provider, String text) {
    }

    private final List<LLMProvider> chain;

    public LLMGateway(List<LLMProvider> providers, @Value("${llm.providers:gemini}") String providerOrder) {
        Map<String, LLMProvider> byName = providers.stream()
            .collect(Collectors.toMap(LLMProvider::getName, Function.identity()));

        List<LLMProvider> ordered = new ArrayList<>();
        for (String name : Arrays.stream(providerOrder.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            LLMProvider provider = byName.get(name);
            if (provider == null) {
                throw new IllegalStateException(String.format(
                    "llm.providers contains unknown provider '%s'. Known: %s", name, byName.keySet()));
            }
            ordered.add(provider);
        }
        this.chain = List.copyOf(ordered);

        log.info("LLM provider chain: {}", chain.stream()
            .map(p -> p.getName() + (p.isConfigured() ? "" : " (not configured, will be skipped)"))
            .toList());
    }

    public LLMReply callLLM(String prompt) {
        LLMException last = new LLMException(LLMException.Kind.NOT_CONFIGURED, "No LLM provider configured");

        for (LLMProvider provider : chain) {
            if (!provider.isConfigured()) {
                last = new LLMException(LLMException.Kind.NOT_CONFIGURED, provider.getName() + " has no API key/URL");
                continue;
            }
            try {
                long start = System.currentTimeMillis();
                String text = provider.callLLM(prompt);
                log.debug("LLM provider {} answered in {} ms", provider.getName(), System.currentTimeMillis() - start);
                return new LLMReply(provider.getName(), text);
            } catch (LLMException e) {
                log.warn("LLM provider {} failed [{}]: {}. Trying next provider.",
                    provider.getName(), e.getKind(), e.getMessage());
                last = e;
            } catch (RuntimeException e) {
                log.warn("LLM provider {} failed unexpectedly: {}. Trying next provider.", provider.getName(), e.toString());
                last = new LLMException(LLMException.Kind.HTTP_ERROR, provider.getName() + " failed: " + e.getMessage(), e);
            }
        }
        throw last;
    }

    public List<String> getProviderNames() {
        return chain.stream().map(LLMProvider::getName).toList();
    }
}
