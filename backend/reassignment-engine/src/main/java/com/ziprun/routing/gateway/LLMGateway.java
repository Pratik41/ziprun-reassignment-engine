package com.ziprun.routing.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
 *
 * Each provider has a circuit breaker (ProviderCircuitBreaker): after
 * llm.circuit.failure-threshold transport failures in a row it is skipped for
 * llm.circuit.cooldown-seconds, so a provider that hangs costs one or two
 * timeouts instead of one on every call. After the cooldown, one trial call
 * decides whether it's back.
 */
@Component
public class LLMGateway {
    private static final Logger log = LoggerFactory.getLogger(LLMGateway.class);

    static final int DEFAULT_FAILURE_THRESHOLD = 2;
    static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(5);

    /** Raw model text plus which provider produced it. */
    public record LLMReply(String provider, String text) {
    }

    private final List<LLMProvider> chain;
    private final Map<String, ProviderCircuitBreaker> breakers = new LinkedHashMap<>();

    @Autowired
    public LLMGateway(List<LLMProvider> providers,
                      @Value("${llm.providers:gemini}") String providerOrder,
                      @Value("${llm.circuit.failure-threshold:2}") int failureThreshold,
                      @Value("${llm.circuit.cooldown-seconds:300}") long cooldownSeconds) {
        this(providers, providerOrder, failureThreshold, Duration.ofSeconds(cooldownSeconds), Clock.systemUTC());
    }

    public LLMGateway(List<LLMProvider> providers, String providerOrder) {
        this(providers, providerOrder, DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN, Clock.systemUTC());
    }

    LLMGateway(List<LLMProvider> providers, String providerOrder, int failureThreshold, Duration cooldown, Clock clock) {
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
            breakers.put(name, new ProviderCircuitBreaker(name, failureThreshold, cooldown, clock));
        }
        this.chain = List.copyOf(ordered);

        log.info("LLM provider chain: {} (pause a provider for {}s after {} failures in a row)", chain.stream()
            .map(p -> p.getName() + (p.isConfigured() ? "" : " (not configured, will be skipped)"))
            .toList(), cooldown.toSeconds(), failureThreshold);
    }

    public LLMReply callLLM(String prompt) {
        return run(provider -> {
            long start = System.currentTimeMillis();
            String text = provider.callLLM(prompt);
            log.debug("LLM provider {} answered in {} ms", provider.getName(), System.currentTimeMillis() - start);
            return text;
        }, null);
    }

    /** Callbacks for a streaming call. */
    public interface StreamSink {
        void chunk(String text);

        /** A provider failed after possibly emitting chunks; anything received so far is void. */
        void providerFailed(String provider, LLMException failure);
    }

    /**
     * Streaming variant of callLLM with the same provider-chain and circuit-breaker semantics.
     */
    public LLMReply streamLLM(String prompt, StreamSink sink) {
        return run(provider -> provider.streamLLM(prompt, sink::chunk), sink);
    }

    public List<String> getProviderNames() {
        return chain.stream().map(LLMProvider::getName).toList();
    }

    /** Breaker state per provider, in chain order (GET /routing/providers). */
    public List<ProviderCircuitBreaker.Status> getProviderStatus() {
        return chain.stream().map(p -> breakers.get(p.getName()).status(p.isConfigured())).toList();
    }

    private LLMReply run(Function<LLMProvider, String> call, StreamSink sink) {
        LLMException last = new LLMException(LLMException.Kind.NOT_CONFIGURED, "No LLM provider configured");
        List<String> paused = new ArrayList<>();
        boolean attempted = false;

        for (LLMProvider provider : chain) {
            String name = provider.getName();
            if (!provider.isConfigured()) {
                last = new LLMException(LLMException.Kind.NOT_CONFIGURED, name + " has no API key/URL");
                continue;
            }
            ProviderCircuitBreaker breaker = breakers.get(name);
            if (!breaker.allowsCall()) {
                paused.add(name);
                continue;
            }
            attempted = true;
            try {
                String text = call.apply(provider);
                breaker.recordSuccess();
                return new LLMReply(name, text);
            } catch (LLMException e) {
                last = e;
            } catch (RuntimeException e) {
                last = new LLMException(LLMException.Kind.HTTP_ERROR, name + " failed: " + e.getMessage(), e);
            }
            boolean opened = breaker.recordFailure(last.getKind() + ": " + last.getMessage());
            log.warn("LLM provider {} failed{} [{}]: {}. {}", name, sink == null ? "" : " while streaming",
                last.getKind(), last.getMessage(),
                opened ? "Pausing it for " + breaker.cooldown().toSeconds() + "s; trying next provider."
                       : "Trying next provider.");
            if (sink != null) {
                sink.providerFailed(name, last);
            }
        }

        if (!attempted && !paused.isEmpty()) {
            throw new LLMException(LLMException.Kind.CIRCUIT_OPEN,
                "All LLM providers are paused after repeated failures: " + paused);
        }
        throw last;
    }
}
