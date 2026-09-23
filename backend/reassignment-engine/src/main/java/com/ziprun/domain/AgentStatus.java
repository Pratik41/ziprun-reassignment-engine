package com.ziprun.domain;

/**
 * Agent availability states.
 * AVAILABLE: Ready to take new orders
 * BUSY: Currently delivering orders, not available for new ones
 * OFFLINE: Unavailable (sick, bike broken, etc.) - FIRES AGENTIC LOOP
 */
public enum AgentStatus {
    AVAILABLE,
    BUSY,
    OFFLINE
}
