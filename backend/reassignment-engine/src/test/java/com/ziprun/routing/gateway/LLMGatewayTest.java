package com.ziprun.routing.gateway;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LLMGatewayTest {

    @Test
    void fallsThroughToNextProviderOnTransportFailure() {
        LLMGateway gateway = new LLMGateway(List.of(
            provider("gemini", true, new LLMException(LLMException.Kind.RATE_LIMITED, "429"), null),
            provider("groq", true, null, "{\"ok\":true}")), "gemini,groq");

        LLMGateway.LLMReply reply = gateway.callLLM("prompt");

        assertThat(reply.provider()).isEqualTo("groq");
        assertThat(reply.text()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void skipsUnconfiguredProviders() {
        LLMGateway gateway = new LLMGateway(List.of(
            provider("gemini", false, null, "never"),
            provider("groq", true, null, "answer")), "gemini,groq");

        assertThat(gateway.callLLM("prompt").provider()).isEqualTo("groq");
    }

    @Test
    void throwsLastFailureWhenEveryProviderFails() {
        LLMGateway gateway = new LLMGateway(List.of(
            provider("gemini", true, new LLMException(LLMException.Kind.RATE_LIMITED, "429"), null),
            provider("groq", true, new LLMException(LLMException.Kind.TIMEOUT, "slow"), null)), "gemini,groq");

        assertThatThrownBy(() -> gateway.callLLM("prompt"))
            .isInstanceOfSatisfying(LLMException.class, e -> assertThat(e.getKind()).isEqualTo(LLMException.Kind.TIMEOUT));
    }

    @Test
    void unknownProviderNameFailsAtStartup() {
        assertThatThrownBy(() -> new LLMGateway(List.of(provider("gemini", true, null, "x")), "gemini,claude"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("claude");
    }

    @Test
    void failingProviderIsPausedAfterTwoFailuresThenRetriedAfterCooldown() {
        MutableClock clock = new MutableClock();
        CountingProvider gemini = new CountingProvider("gemini", new LLMException(LLMException.Kind.TIMEOUT, "slow"));
        LLMGateway gateway = new LLMGateway(List.of(gemini, provider("groq", true, null, "answer")),
            "gemini,groq", 2, Duration.ofMinutes(5), clock);

        gateway.callLLM("p1");
        gateway.callLLM("p2");
        assertThat(gemini.calls).isEqualTo(2);
        assertThat(gateway.getProviderStatus().get(0).state()).isEqualTo(ProviderCircuitBreaker.State.OPEN);

        // paused: groq answers without waiting on gemini
        assertThat(gateway.callLLM("p3").provider()).isEqualTo("groq");
        assertThat(gemini.calls).isEqualTo(2);

        // cooldown over: one trial call; still failing -> paused again straight away
        clock.advance(Duration.ofMinutes(5));
        gateway.callLLM("p4");
        assertThat(gemini.calls).isEqualTo(3);
        gateway.callLLM("p5");
        assertThat(gemini.calls).isEqualTo(3);

        // recovered: the trial succeeds and the breaker closes
        clock.advance(Duration.ofMinutes(5));
        gemini.failure = null;
        assertThat(gateway.callLLM("p6").provider()).isEqualTo("gemini");
        assertThat(gateway.getProviderStatus().get(0).state()).isEqualTo(ProviderCircuitBreaker.State.CLOSED);
        assertThat(gateway.getProviderStatus().get(0).consecutiveFailures()).isZero();
    }

    @Test
    void aSingleFailureDoesNotPauseAProvider() {
        CountingProvider gemini = new CountingProvider("gemini", new LLMException(LLMException.Kind.RATE_LIMITED, "429"));
        LLMGateway gateway = new LLMGateway(List.of(gemini, provider("groq", true, null, "answer")),
            "gemini,groq", 2, Duration.ofMinutes(5), new MutableClock());

        gateway.callLLM("p1");
        gemini.failure = null;
        assertThat(gateway.callLLM("p2").provider()).isEqualTo("gemini");
    }

    @Test
    void allProvidersPausedFailsFastWithCircuitOpen() {
        CountingProvider gemini = new CountingProvider("gemini", new LLMException(LLMException.Kind.TIMEOUT, "slow"));
        LLMGateway gateway = new LLMGateway(List.of(gemini), "gemini", 1, Duration.ofMinutes(5), new MutableClock());

        assertThatThrownBy(() -> gateway.callLLM("p1")).isInstanceOf(LLMException.class);
        assertThatThrownBy(() -> gateway.callLLM("p2"))
            .isInstanceOfSatisfying(LLMException.class, e -> assertThat(e.getKind()).isEqualTo(LLMException.Kind.CIRCUIT_OPEN));
        assertThat(gemini.calls).isEqualTo(1);
    }

    private static final class CountingProvider implements LLMProvider {
        private final String name;
        LLMException failure;
        int calls;

        CountingProvider(String name, LLMException failure) {
            this.name = name;
            this.failure = failure;
        }

        public String getName() { return name; }
        public boolean isConfigured() { return true; }
        public String callLLM(String prompt) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return "answer from " + name;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T09:00:00Z");

        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static LLMProvider provider(String name, boolean configured, LLMException failure, String answer) {
        return new LLMProvider() {
            public String getName() { return name; }
            public boolean isConfigured() { return configured; }
            public String callLLM(String prompt) {
                if (failure != null) {
                    throw failure;
                }
                return answer;
            }
        };
    }
}
