package com.ziprun.routing.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Circuit breaker for one LLM provider, so a provider that keeps failing doesn't
 * cost every AI call a full timeout before the next provider is tried.
 *
 *   CLOSED    - calls go through; consecutive failures are counted
 *   OPEN      - after failureThreshold failures in a row: skipped until the cooldown ends
 *   HALF_OPEN - cooldown over: the next call is a trial. Success closes the
 *               breaker; failure opens it again for another cooldown.
 *
 * Thread-safe: routing runs on HTTP threads and the async agentic loop at once.
 */
public class ProviderCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Snapshot for GET /routing/providers. */
    public record Status(String provider, boolean configured, State state, int consecutiveFailures,
                         Instant openUntil, String lastError) {
    }

    private final String provider;
    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;

    private int consecutiveFailures;
    private Instant openUntil;
    private String lastError;

    public ProviderCircuitBreaker(String provider, int failureThreshold, Duration cooldown, Clock clock) {
        this.provider = provider;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** False while OPEN: the gateway skips this provider. */
    public synchronized boolean allowsCall() {
        return state() != State.OPEN;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntil = null;
        lastError = null;
    }

    /** @return true if this failure opened the breaker */
    public synchronized boolean recordFailure(String error) {
        boolean trial = state() == State.HALF_OPEN;
        consecutiveFailures++;
        lastError = error;
        if (trial || consecutiveFailures >= failureThreshold) {
            openUntil = clock.instant().plus(cooldown);
            return true;
        }
        return false;
    }

    public synchronized State state() {
        if (openUntil == null) {
            return State.CLOSED;
        }
        return clock.instant().isBefore(openUntil) ? State.OPEN : State.HALF_OPEN;
    }

    public synchronized Status status(boolean configured) {
        State state = state();
        return new Status(provider, configured, state, consecutiveFailures,
            state == State.OPEN ? openUntil : null, lastError);
    }

    public Duration cooldown() {
        return cooldown;
    }
}
