package com.ziprun;

import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

/** Helpers for reading Server-Sent Events from MockMvc responses. */
public final class SseTestSupport {

    private SseTestSupport() {
    }

    /**
     * Waits (up to 10s) until a final event, "suggestion" or "error", has arrived
     * <em>completely</em>. The emitter writes the event name and its data in separate
     * writes, so seeing "event:suggestion" isn't enough: the frame is only complete
     * once the blank line that ends it has been written.
     */
    public static String awaitFinalEvent(MockHttpServletResponse response) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        String body = response.getContentAsString();
        while (!finished(body) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            body = response.getContentAsString();
        }
        return body;
    }

    static boolean finished(String body) {
        for (String event : List.of("event:suggestion", "event:error")) {
            int at = body.indexOf(event);
            if (at >= 0 && body.indexOf("\n\n", at) >= 0) {
                return true;
            }
        }
        return false;
    }
}
