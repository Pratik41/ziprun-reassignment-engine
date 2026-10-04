package com.ziprun.domain.event;

import org.springframework.context.ApplicationEvent;

/**
 * Domain event: an AVAILABLE agent became BUSY (stopped taking new orders).
 *
 * Unlike OFFLINE, their own orders are not stranded: they are still delivering.
 * But open suggestions that recommend them are now stale, so ReplanEventHandler
 * withdraws those and re-plans the orders that were waiting on them.
 */
public class AgentBusyEvent extends ApplicationEvent {
    private final String agentId;
    private final String agentName;

    public AgentBusyEvent(Object source, String agentId, String agentName) {
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
