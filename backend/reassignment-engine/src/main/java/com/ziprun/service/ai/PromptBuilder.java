package com.ziprun.service.ai;

import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.routing.RoutingContext;
import com.ziprun.routing.Zones;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds AI prompts for routing decisions (see ADR-7).
 *
 * The two prompts are written for two different situations, not one template
 * with a flag:
 *
 *  Initial  - routine request from ops. Goal: a sensible, balanced assignment.
 *             Framing: "recommend an agent for this order". The SLA monitor uses
 *             it too, with a situation line saying the deadline is at risk.
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

    private static final String ROSTER_LEGEND = """
        "active" = orders they are carrying now; "pending" = suggestions already queued for them; "effective" = active + pending.
        "capacity" = the most orders they should carry (agents already at capacity are not listed unless everyone is).
        "distance to pickup" = same (already in the pickup zone), neighbour (next zone over), far, or unknown.""";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public static String buildInitialAssignmentPrompt(Order order, List<Agent> availableAgents, RoutingContext context) {
        return """
            You are the dispatch advisor for ZipRun, a same-day delivery fleet.
            An operations manager has asked for a routing recommendation for one order.

            SITUATION: %s

            ORDER
            %s

            AVAILABLE AGENTS
            %s
            %s

            HOW TO DECIDE
            1. Prefer the agent with the lowest effective load: fewer orders ahead means a faster pickup.
            2. Prefer agents in or next to the pickup zone (distance column); a nearby agent with one more order can beat a far one.
            3. Use the order description where it matters (fragile, perishable, documents), but only with facts given here.
            4. If two agents are tied, say so and lower your confidence.

            %s""".formatted(situation(order, context), describeOrder(order), ROSTER_LEGEND,
                formatAgentRoster(order, availableAgents, context), OUTPUT_CONTRACT);
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
            %s
            %s

            RECOVERY PRIORITIES
            1. Speed: this order is already delayed. Prefer the agent who can start soonest (lowest effective load), and one in or next to the pickup zone.
            2. Spread the batch: avoid stacking several stranded orders on one agent when a comparable alternative exists.
            3. Thin roster: if there are fewer available agents than stranded orders, say so in the reasoning and lower confidence. Ops may need to call in extra capacity.
            4. Start the reasoning by naming this as a recovery from %s going offline.

            %s""".formatted(
                context.failedAgentName(), context.failedAgentId(),
                context.strandedOrderCount(), formatStrandedBatch(order, context),
                describeOrder(order),
                ROSTER_LEGEND,
                formatAgentRoster(order, availableAgents, context),
                context.failedAgentName(),
                OUTPUT_CONTRACT);
    }

    private static String describeOrder(Order order) {
        boolean isNew = order.getAssignedAgentId() == null; // a draft from the "New order" dialog
        StringBuilder sb = new StringBuilder()
            .append("- id: ").append(isNew ? "(not created yet)" : order.getId()).append('\n')
            .append("- description: ").append(order.getDescription()).append('\n')
            .append("- currently assigned to: ").append(isNew ? "nobody (new order)" : order.getAssignedAgentId());
        if (order.getPickupZone() != null || order.getDropoffZone() != null) {
            sb.append('\n').append("- pickup zone: ").append(orUnknown(Zones.name(order.getPickupZone())))
                .append(", drop-off zone: ").append(orUnknown(Zones.name(order.getDropoffZone())));
        }
        if (order.getSlaDeadline() != null) {
            long minutes = Duration.between(LocalDateTime.now(), order.getSlaDeadline()).toMinutes();
            sb.append('\n').append("- deliver by: ").append(order.getSlaDeadline().format(TIME))
                .append(minutes >= 0 ? " (" + minutes + " min from now)" : " (already " + -minutes + " min late)");
        }
        return sb.toString();
    }

    private static String situation(Order order, RoutingContext context) {
        if (context.isSlaRisk()) {
            return "this order is at risk of missing its delivery deadline with its current agent ("
                + order.getAssignedAgentId() + "). Recommend agents who could get it there sooner: low effective "
                + "load and close to the pickup. Say in the reasoning why they would be faster than keeping it where it is.";
        }
        return "routine assignment request. Nothing has failed; aim for a balanced fleet.";
    }

    private static String orUnknown(String value) {
        return value == null ? "unknown" : value;
    }

    private static String formatStrandedBatch(Order current, RoutingContext context) {
        return context.strandedOrders().stream()
            .map(o -> "  * " + o.getId() + " - " + o.getDescription()
                + (o.getId().equals(current.getId()) ? "   <- THIS ORDER" : ""))
            .collect(Collectors.joining("\n"));
    }

    static String formatAgentRoster(Order order, List<Agent> agents, RoutingContext context) {
        String header = "| id | name | active | pending | effective | capacity | zone | distance to pickup |\n"
            + "|----|------|--------|---------|-----------|----------|------|--------------------|";
        String rows = agents.stream()
            .map(agent -> String.format("| %s | %s | %d | %d | %d | %s | %s | %s |",
                agent.getId(),
                agent.getName(),
                agent.getActiveOrderCount(),
                context.pendingFor(agent),
                context.effectiveLoad(agent),
                context.capacityOf(agent) > 0 ? String.valueOf(context.capacityOf(agent)) : "no limit",
                orUnknown(Zones.name(agent.getCurrentZone())),
                order.getPickupZone() == null ? "n/a"
                    : Zones.distance(order.getPickupZone(), agent.getCurrentZone()).name().toLowerCase()))
            .collect(Collectors.joining("\n"));
        return header + "\n" + rows;
    }
}
