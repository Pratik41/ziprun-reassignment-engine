package com.ziprun.controller.dto;

import com.ziprun.domain.Activity;

import java.time.LocalDateTime;

/** An activity-log entry as the API shows it (see AgentView for why this isn't the entity). */
public record ActivityView(Long id, LocalDateTime at, String type, String actor, String orderId, String agentId,
                           String suggestionId, String message) {

    public static ActivityView from(Activity a) {
        return new ActivityView(a.getId(), a.getAt(), a.getType(), a.getActor(), a.getOrderId(), a.getAgentId(),
            a.getSuggestionId(), a.getMessage());
    }
}
