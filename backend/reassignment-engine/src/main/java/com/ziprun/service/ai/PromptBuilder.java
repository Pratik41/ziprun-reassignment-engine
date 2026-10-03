package com.ziprun.service.ai;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingContext;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds AI prompts for routing decisions (see ADR-7).
 *
 * The two prompts are written for two different situations, not one template
 * with a flag:
 *
 *  Initial  - routine request from ops. Goal: a sensible, balanced assignment.
 *             Framing: "recommend an agent for this order".
 *
 *  Re-plan  - an incident report. Who failed, that their assignments are void,
 *             the full list of stranded orders in this batch, which of them is
 *             being decided now, and recovery priorities (speed, spreading the
 *             batch, flagging a thin roster).
 *
 * Both share the same roster table and output schema so parsing and
 * validation stay identical.
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    private static final String OUTPUT_CONTRACT = """
        OUTPUT FORMAT
        Respond with JSON only: no markdown fences, no text before or after it.
        {"recommendations":[{"agent_id":"<id copied exactly from the table>","confidence":<number 0.0-1.0>,"reasoning":"<1-3 sentences>"}]}
        - Rank up to 3 agents, best first.
        - agent_id MUST be one of the ids in the AVAILABLE AGENTS table. Never invent an id.
        - confidence: 0.85-1.0 when one agent is clearly best; 0.6-0.85 when candidates are close; below 0.6 when you are unsure.
        - reasoning is shown verbatim to an operations manager deciding whether to accept. Name the agent, cite the load numbers, and state the trade-off. Do not invent facts (location, rating, vehicle) that are not in this prompt.
        """;

    public static String buildInitialAssignmentPrompt(Order order, List<Agent> availableAgents, RoutingContext context) {
        return """
            You are the dispatch advisor for ZipRun, a same-day delivery fleet.
            An operations manager has asked for a routing recommendation for one order.

            SITUATION: routine assignment request. Nothing has failed; aim for a balanced fleet.

            ORDER
            %s

            AVAILABLE AGENTS
            "active" = orders they are carrying now; "pending" = suggestions already queued for them; "effective" = active + pending.
            %s

            HOW TO DECIDE
            1. Prefer the agent with the lowest effective load: fewer orders ahead means a faster pickup.
            2. Use the order description where it matters (fragile, perishable, documents), but only with facts given here.
            3. If two agents are tied, say so and lower your confidence.

            %s""".formatted(describeOrder(order), formatAgentRoster(availableAgents, context), OUTPUT_CONTRACT);
    }

    public static String buildReplanPrompt(Order order, List<Agent> availableAgents, RoutingContext context) {
        return """
            You are the dispatch advisor for ZipRun, a same-day delivery fleet.
            This is a RECOVERY situation, not a routine assignment.

            INCIDENT REPORT
            - Agent %s (%s) has gone OFFLINE mid-shift. Every order assigned to them is now void: they will not pick up or deliver anything.
            - %d order(s) are stranded and are being re-planned one at a time in this batch:
            %s
            - Suggestions already queued for earlier orders in this batch appear in the "pending" column below, so you can see where they are heading.

            ORDER TO RECOVER NOW
            %s

            AVAILABLE AGENTS (the offline agent is excluded)
            "active" = orders they are carrying now; "pending" = suggestions already queued for them; "effective" = active + pending.
            %s

            RECOVERY PRIORITIES
            1. Speed: this order is already delayed. Prefer the agent who can start soonest (lowest effective load).
            2. Spread the batch: avoid stacking several stranded orders on one agent when a comparable alternative exists.
            3. Thin roster: if there are fewer available agents than stranded orders, say so in the reasoning and lower confidence. Ops may need to call in extra capacity.
            4. Start the reasoning by naming this as a recovery from %s going offline.

            %s""".formatted(
                context.failedAgentName(), context.failedAgentId(),
                context.strandedOrderCount(), formatStrandedBatch(order, context),
                describeOrder(order),
                formatAgentRoster(availableAgents, context),
                context.failedAgentName(),
                OUTPUT_CONTRACT);
    }

    private static String describeOrder(Order order) {
        StringBuilder sb = new StringBuilder()
            .append("- id: ").append(order.getId()).append('\n')
            .append("- description: ").append(order.getDescription()).append('\n')
            .append("- currently assigned to: ").append(order.getAssignedAgentId());
        if (order.getPickupZone() != null || order.getDropoffZone() != null) {
            sb.append('\n').append("- zones: ").append(order.getPickupZone()).append(" -> ").append(order.getDropoffZone());
        }
        return sb.toString();
    }

    private static String formatStrandedBatch(Order current, RoutingContext context) {
        return context.strandedOrders().stream()
            .map(o -> "  * " + o.getId() + " - " + o.getDescription()
                + (o.getId().equals(current.getId()) ? "   <- THIS ORDER" : ""))
            .collect(Collectors.joining("\n"));
    }

    static String formatAgentRoster(List<Agent> agents, RoutingContext context) {
        String header = "| id | name | active | pending | effective |\n|----|------|--------|---------|-----------|";
        String rows = agents.stream()
            .map(agent -> String.format("| %s | %s | %d | %d | %d |",
                agent.getId(),
                agent.getName(),
                agent.getActiveOrderCount(),
                context.pendingFor(agent),
                context.effectiveLoad(agent)))
            .collect(Collectors.joining("\n"));
        return header + "\n" + rows;
    }
}
