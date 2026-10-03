package com.ziprun.routing;

/**
 * Optional observer for a routing call that wants to watch the reasoning as
 * it is produced (the SSE endpoint). Strategies that can stream (AI) push
 * text; others ignore it. NONE means nobody is listening, so stream nothing.
 */
public interface ReasoningListener {

    ReasoningListener NONE = text -> { };

    /** Next fragment of reasoning text, in order. */
    void token(String text);

    /** Text streamed so far is void (provider failed or fallback kicked in); reason is human-readable. */
    default void restart(String reason) {
    }
}
