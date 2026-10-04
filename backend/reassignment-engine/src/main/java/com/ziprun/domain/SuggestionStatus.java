package com.ziprun.domain;

/**
 * Suggestion lifecycle.
 * PENDING: Suggestion created, waiting for ops approval/rejection
 * ACCEPTED: Ops approved, order is reassigned to recommended agent
 * REJECTED: Ops rejected (or ops accepted a different suggestion for the same order)
 * EXPIRED: The system withdrew it because the world changed before ops decided:
 *          the recommended agent went OFFLINE, or ops kept the order with its
 *          original agent. Distinct from REJECTED so it's clear no human said no.
 */
public enum SuggestionStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    EXPIRED
}
