package com.ziprun.routing.gateway;

import org.junit.jupiter.api.Test;

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
