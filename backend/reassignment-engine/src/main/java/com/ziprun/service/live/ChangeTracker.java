package com.ziprun.service.live;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JPA entity listener on every entity: whenever a row is written, tell LiveUpdates
 * which kind of data changed. Hooking the persistence layer (rather than each
 * service) means no write path can forget to notify the console.
 *
 * Inside a transaction the notification waits until commit, so a console never
 * re-reads uncommitted (or later rolled-back) data.
 */
@Component
public class ChangeTracker {

    private LiveUpdates live;

    // Hibernate creates entity listeners through Spring (SpringBeanContainer), so injection works.
    // Lazy: the listener is built while the EntityManagerFactory is still starting.
    @Autowired
    void setLive(@Lazy LiveUpdates live) {
        this.live = live;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void onWrite(Object entity) {
        afterCommit(topicOf(entity));
    }

    /**
     * For bulk UPDATE queries, which bypass entity listeners: notify consoles once the
     * current transaction commits (or right away outside one).
     */
    public void afterCommit(String topic) {
        if (live == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    live.changed(topic);
                }
            });
        } else {
            live.changed(topic);
        }
    }

    static String topicOf(Object entity) {
        return switch (entity.getClass().getSimpleName()) {
            case "Agent" -> "agents";
            case "Order" -> "orders";
            case "ReassignmentSuggestion" -> "suggestions";
            case "Activity" -> "activity";
            default -> "settings";
        };
    }
}
