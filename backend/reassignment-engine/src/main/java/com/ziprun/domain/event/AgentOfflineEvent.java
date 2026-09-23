package com.ziprun.domain.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Domain event: Agent has gone OFFLINE
 * Published by Agent service when status changes to OFFLINE
 * Listened to by ReplanEventHandler which triggers async re-planning
 *
 * This decouples the agent status update from the re-planning logic,
 * allowing both to evolve independently (follows principle in ADR-4)
 */
@Getter
public class AgentOfflineEvent extends ApplicationEvent {
    private final String agentId;
    private final String agentName;

    public AgentOfflineEvent(Object source, String agentId, String agentName) {
        super(source);
        this.agentId = agentId;
        this.agentName = agentName;
    }
}
