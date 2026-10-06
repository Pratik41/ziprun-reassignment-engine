package com.ziprun.controller.dto;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;

import java.time.LocalDateTime;

/**
 * An agent as the API shows it. Response records (rather than the JPA entities) keep
 * internals such as the optimistic-lock version out of the API, and let the tables
 * change without changing what clients see.
 */
public record AgentView(String id, String name, AgentStatus status, int activeOrderCount, String currentZone,
                        Integer maxCapacity, LocalDateTime lastHeartbeatAt, String statusNote) {

    public static AgentView from(Agent a) {
        return new AgentView(a.getId(), a.getName(), a.getStatus(),
            a.getActiveOrderCount() == null ? 0 : a.getActiveOrderCount(), a.getCurrentZone(), a.getMaxCapacity(),
            a.getLastHeartbeatAt(), a.getStatusNote());
    }
}
