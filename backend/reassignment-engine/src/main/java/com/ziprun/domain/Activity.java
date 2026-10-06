package com.ziprun.domain;

import com.ziprun.service.live.ChangeTracker;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One entry in the activity log: who did what, when.
 *
 * type and actor are stored as plain strings (not database enums) so new
 * activity types can be added without a schema migration.
 */
@Entity
@EntityListeners(ChangeTracker.class) // pushes "data changed" to open consoles (GET /events)
@Table(name = "activity_log", indexes = @Index(name = "idx_activity_at", columnList = "at"))
public class Activity {

    public enum Type {
        AGENT_STATUS,
        AGENT_AUTO_OFFLINE,
        ORDER_CREATED,
        ORDER_DELIVERED,
        ORDER_REASSIGNED,
        ORDER_KEPT,
        SUGGESTION_CREATED,
        SUGGESTION_ACCEPTED,
        SUGGESTION_REJECTED,
        SUGGESTIONS_WITHDRAWN,
        STRATEGY_SWITCHED,
        AGENT_UPDATED,
        SLA_AT_RISK
    }

    /** "ops" = a person using the console or API; "system" = the re-planning loop or heartbeat monitor. */
    public enum Actor { OPS, SYSTEM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime at;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 10)
    private String actor;

    private String orderId;
    private String agentId;
    private String suggestionId;

    @Column(nullable = false, length = 500)
    private String message;

    protected Activity() {
    }

    public Activity(Type type, Actor actor, String message, String orderId, String agentId, String suggestionId) {
        this.at = LocalDateTime.now();
        this.type = type.name();
        this.actor = actor.name().toLowerCase();
        this.message = message.length() <= 500 ? message : message.substring(0, 497) + "...";
        this.orderId = orderId;
        this.agentId = agentId;
        this.suggestionId = suggestionId;
    }

    public Long getId() { return id; }
    public LocalDateTime getAt() { return at; }
    public String getType() { return type; }
    public String getActor() { return actor; }
    public String getOrderId() { return orderId; }
    public String getAgentId() { return agentId; }
    public String getSuggestionId() { return suggestionId; }
    public String getMessage() { return message; }
}
