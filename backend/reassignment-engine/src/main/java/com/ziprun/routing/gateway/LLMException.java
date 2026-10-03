package com.ziprun.routing.gateway;

import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * Every way an AI recommendation can fail, as one typed exception.
 *
 * The kind is what the fallback log line and the suggestion's source show
 * (e.g. "rule-based (AI fallback: TIMEOUT)"), so each failure mode is
 * distinguishable in production instead of collapsing into "AI returned null".
 */
public class LLMException extends RuntimeException {

    public enum Kind {
        /** Provider in the chain has no API key / URL configured. */
        NOT_CONFIGURED,
        /** Connect or read timeout (llm.timeout-ms). */
        TIMEOUT,
        /** HTTP 429: quota or rate limit exhausted. */
        RATE_LIMITED,
        /** Any other HTTP error, auth failure or connection refusal. */
        HTTP_ERROR,
        /** Provider returned no text. */
        EMPTY_RESPONSE,
        /** Text was not the JSON shape we asked for. */
        UNPARSEABLE,
        /** Every recommended agent ID was absent from the roster we sent. */
        HALLUCINATED_AGENT,
        /** JSON parsed but values were unusable (confidence out of range, blank reasoning). */
        INVALID_RESPONSE
    }

    private final Kind kind;

    public LLMException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LLMException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * Classifies a RestClient failure from the named provider.
     */
    public static LLMException fromHttp(String provider, RestClientException e) {
        if (e instanceof HttpStatusCodeException http) {
            if (http.getStatusCode().value() == 429) {
                return new LLMException(Kind.RATE_LIMITED, provider + " rate limited / quota exhausted (429)", e);
            }
            return new LLMException(Kind.HTTP_ERROR, provider + " returned HTTP " + http.getStatusCode().value(), e);
        }
        if (e instanceof ResourceAccessException && isTimeout(e)) {
            return new LLMException(Kind.TIMEOUT, provider + " timed out", e);
        }
        return new LLMException(Kind.HTTP_ERROR, provider + " call failed: " + e.getMessage(), e);
    }

    /**
     * Classifies a non-2xx status seen while reading a streaming response.
     */
    public static LLMException fromStatus(String provider, int status) {
        return status == 429
            ? new LLMException(Kind.RATE_LIMITED, provider + " rate limited / quota exhausted (429)")
            : new LLMException(Kind.HTTP_ERROR, provider + " returned HTTP " + status);
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "LLMException{" + kind + ": " + getMessage() + '}';
    }
}
