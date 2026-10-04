package com.ziprun.domain.event;

import org.springframework.context.ApplicationEvent;

/**
 * Domain event: an agent became AVAILABLE (from BUSY or OFFLINE).
 *
 * Fleet capacity just grew, so suggestions computed for stranded orders while
 * fewer agents were available may now be lopsided (e.g. all on the one agent who
 * was available), and orders that had no candidate may now have one.
 * ReplanEventHandler re-plans every REASSIGNMENT_PENDING order against the new roster.
 */
public class AgentAvailableEvent extends ApplicationEvent {
    private final String agentId;
    private final String agentName;

    public AgentAvailableEvent(Object source, String agentId, String agentName) {
        super(source);
        this.agentId = agentId;
        this.agentName = agentName;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentName() {
        return agentName;
    }
}
