package com.ziprun.controller.dto;

import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;

import java.time.LocalDateTime;

/** An order as the API shows it (see AgentView for why this isn't the entity). */
public record OrderView(String id, String description, String assignedAgentId, OrderStatus status,
                        LocalDateTime createdAt, String pickupZone, String dropoffZone, LocalDateTime slaDeadline,
                        String recommendedAgentId, Boolean followedRecommendation) {

    public static OrderView from(Order o) {
        return new OrderView(o.getId(), o.getDescription(), o.getAssignedAgentId(), o.getStatus(), o.getCreatedAt(),
            o.getPickupZone(), o.getDropoffZone(), o.getSlaDeadline(), o.getRecommendedAgentId(),
            o.getFollowedRecommendation());
    }
}
