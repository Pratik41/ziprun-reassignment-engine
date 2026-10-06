package com.ziprun.domain;

import com.ziprun.service.live.ChangeTracker;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Key/value settings changed at runtime that must survive a restart
 * (e.g. the active routing strategy chosen in the UI).
 */
@Entity
@EntityListeners(ChangeTracker.class) // pushes "data changed" to open consoles (GET /events)
@Table(name = "app_settings")
public class AppSetting {

    public static final String ROUTING_STRATEGY = "routing.strategy";

    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 500)
    private String value;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected AppSetting() {
    }

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = LocalDateTime.now();
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
