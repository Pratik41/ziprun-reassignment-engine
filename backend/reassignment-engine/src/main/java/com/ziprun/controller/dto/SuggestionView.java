package com.ziprun.controller.dto;

import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;

import java.time.LocalDateTime;

/** A suggestion as the API shows it (see AgentView for why this isn't the entity). */
public record SuggestionView(String id, String orderId, String recommendedAgentId, Double confidence, String reasoning,
                             SuggestionStatus status, TriggerReason triggerReason, String source, Long routingMillis,
                             LocalDateTime createdAt, LocalDateTime decidedAt) {

    public static SuggestionView from(ReassignmentSuggestion s) {
        return new SuggestionView(s.getId(), s.getOrderId(), s.getRecommendedAgentId(), s.getConfidence(),
            s.getReasoning(), s.getStatus(), s.getTriggerReason(), s.getSource(), s.getRoutingMillis(),
            s.getCreatedAt(), s.getDecidedAt());
    }
}
