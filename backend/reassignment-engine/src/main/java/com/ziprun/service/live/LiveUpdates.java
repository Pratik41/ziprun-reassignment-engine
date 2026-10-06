package com.ziprun.service.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes "data changed" events to every open console (GET /events, Server-Sent Events),
 * so the UI refreshes the moment something happens instead of polling.
 *
 * Events carry only which kinds of data changed (agents, orders, suggestions,
 * activity, settings), never the data itself: the console re-reads through the
 * normal API, so there is one source of truth and nothing to keep in sync.
 *
 * Changes are coalesced for COALESCE_MS, so a re-plan that writes twenty rows
 * produces one event, not twenty. A comment line every 25 s keeps idle
 * connections open through proxies.
 */
@Service
public class LiveUpdates {
    private static final Logger log = LoggerFactory.getLogger(LiveUpdates.class);

    static final long COALESCE_MS = 150;
    /** Clients reconnect automatically (EventSource) when this expires. */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Set<String> pending = new TreeSet<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-updates");
        t.setDaemon(true);
        return t;
    });
    private boolean flushScheduled;

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("clients", emitters.size())));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /** Record that some kind of data changed; sent to clients shortly after. */
    public void changed(String topic) {
        synchronized (pending) {
            pending.add(topic);
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        flusher.schedule(this::flush, COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    private void flush() {
        List<String> topics;
        synchronized (pending) {
            topics = List.copyOf(pending);
            pending.clear();
            flushScheduled = false;
        }
        if (!topics.isEmpty()) {
            send(SseEmitter.event().name("change").data(Map.of("topics", topics)));
        }
    }

    @Scheduled(fixedRate = 25_000)
    public void keepAlive() {
        send(SseEmitter.event().comment("keep-alive"));
    }

    public int clientCount() {
        return emitters.size();
    }

    private void send(SseEmitter.SseEventBuilder event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (Exception e) {
                // client went away; drop it (it reconnects if it's still open)
                emitters.remove(emitter);
                log.debug("Live client dropped: {}", e.toString());
            }
        }
    }
}
