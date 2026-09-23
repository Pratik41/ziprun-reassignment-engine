package com.ziprun.domain;

/**
 * Order lifecycle states.
 * ASSIGNED: Order has been assigned to an agent
 * REASSIGNMENT_PENDING: Assigned agent went offline, awaiting reassignment decision
 * REASSIGNED: Ops approved a reassignment suggestion, order now assigned to new agent
 * DELIVERED: Order has been delivered
 */
public enum OrderStatus {
    ASSIGNED,
    REASSIGNMENT_PENDING,
    REASSIGNED,
    DELIVERED
}
