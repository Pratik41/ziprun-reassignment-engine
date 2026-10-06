package com.ziprun.domain;

/**
 * Why was a reassignment suggestion created?
 * INITIAL: Manual request (ops clicked "suggest" button)
 * AGENT_OFFLINE: Agentic loop (agent went offline, system auto-detected)
 * SLA_RISK: SLA monitor (order likely to miss its delivery deadline with its current agent)
 *
 * Used to show a re-plan badge in UI: helps ops see which suggestions came from
 * the automatic agentic loop vs manual request.
 */
public enum TriggerReason {
    INITIAL,
    AGENT_OFFLINE,
    SLA_RISK
}
