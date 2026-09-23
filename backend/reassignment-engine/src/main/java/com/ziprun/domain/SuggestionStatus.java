package com.ziprun.domain;

/**
 * Suggestion lifecycle.
 * PENDING: Suggestion created, waiting for ops approval/rejection
 * ACCEPTED: Ops approved, order is reassigned to recommended agent
 * REJECTED: Ops rejected, order stays with current agent (may need manual review)
 */
public enum SuggestionStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}
