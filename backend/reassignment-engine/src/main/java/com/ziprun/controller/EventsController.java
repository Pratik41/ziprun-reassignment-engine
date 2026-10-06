package com.ziprun.controller;

import com.ziprun.service.live.LiveUpdates;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /events - Server-Sent Events stream for the console.
 *
 *   hello  {clients}             on connect
 *   change {topics: [...]}       some data changed: agents, orders, suggestions, activity, settings
 *   (comment lines every 25 s keep the connection open)
 *
 * The console re-reads what it needs through the normal API when a change arrives.
 */
@RestController
public class EventsController {

    private final LiveUpdates live;

    public EventsController(LiveUpdates live) {
        this.live = live;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return live.subscribe();
    }
}
