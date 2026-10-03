package com.ziprun.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle states.
 * ASSIGNED: Order has been assigned to an agent (morning manual assignment)
 * REASSIGNMENT_PENDING: Assigned agent went offline, awaiting ops decision on a suggestion
 * REASSIGNED: Ops approved a reassignment, order now assigned to new agent
 * DELIVERED: Order has been delivered (terminal)
 *
 * The allowed transitions live here, on the enum, so the state machine is part
 * of the domain model rather than buried in a service:
 *
 *   ASSIGNED             -> REASSIGNMENT_PENDING | REASSIGNED | DELIVERED
 *   REASSIGNMENT_PENDING -> REASSIGNED | ASSIGNED
 *   REASSIGNED           -> REASSIGNMENT_PENDING | REASSIGNED | DELIVERED
 *   DELIVERED            -> (terminal)
 */
public enum OrderStatus {
    ASSIGNED,
    REASSIGNMENT_PENDING,
    REASSIGNED,
    DELIVERED;

    /** Statuses in which an order is still "live" and owned by its assigned agent. */
    public static final Set<OrderStatus> ACTIVE = EnumSet.of(ASSIGNED, REASSIGNMENT_PENDING, REASSIGNED);

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            // ASSIGNED -> REASSIGNED: ops accepts a manually requested (INITIAL) suggestion
            case ASSIGNED -> next == REASSIGNMENT_PENDING || next == REASSIGNED || next == DELIVERED;
            // REASSIGNMENT_PENDING -> ASSIGNED: original agent came back and ops keeps them
            case REASSIGNMENT_PENDING -> next == REASSIGNED || next == ASSIGNED;
            // REASSIGNED -> REASSIGNMENT_PENDING: the new agent also went offline
            case REASSIGNED -> next == REASSIGNMENT_PENDING || next == REASSIGNED || next == DELIVERED;
            case DELIVERED -> false;
        };
    }
}
