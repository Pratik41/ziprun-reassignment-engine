package com.ziprun.controller;

import com.ziprun.controller.dto.AgentView;
import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.exception.NotFoundException;
import com.ziprun.service.agent.AgentService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Agent Controller: REST API for agent management.
 *
 * Endpoints:
 * POST   /agents              - Create agent (201)
 * GET    /agents?status=      - List agents (optionally filtered by status)
 * GET    /agents/{id}         - Get single agent
 * PATCH  /agents/{id}         - Set zone and capacity
 * POST   /agents/{id}/heartbeat - Phone app check-in (missing heartbeats => automatic OFFLINE)
 * PATCH  /agents/{id}/status  - Update status; OFFLINE fires the agentic loop
 *                                asynchronously, so this returns immediately
 */
@RestController
@RequestMapping("/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentView createAgent(@Valid @RequestBody CreateAgentRequest request) {
        Agent agent = new Agent();
        agent.setId(request.id());
        agent.setName(request.name());
        agent.setStatus(AgentStatus.AVAILABLE);
        agent.setActiveOrderCount(0);
        return AgentView.from(agentService.save(agent));
    }

    @GetMapping
    public List<AgentView> listAgents(@RequestParam(name = "status", required = false) String status,
                                @RequestParam(name = "page", required = false) Integer page,
                                @RequestParam(name = "size", required = false) Integer size,
                                HttpServletResponse response) {
        AgentStatus filter = status == null || status.isBlank() ? null : EnumParam.parse(AgentStatus.class, status, "agent status");
        return Paging.respond(agentService.list(filter, Paging.of(page, size, Sort.by("name"))), AgentView::from, response);
    }

    @GetMapping("/{id}")
    public AgentView getAgent(@PathVariable String id) {
        return AgentView.from(agentService.findById(id).orElseThrow(() -> new NotFoundException("Agent", id)));
    }

    /**
     * PATCH /agents/{id}/status - { "status": "OFFLINE" }
     */
    @PatchMapping("/{id}/status")
    public AgentView updateAgentStatus(@PathVariable String id, @Valid @RequestBody UpdateStatusRequest request) {
        return AgentView.from(agentService.updateStatus(id, EnumParam.parse(AgentStatus.class, request.status(), "agent status")));
    }

    /**
     * PATCH /agents/{id} - { "currentZone": "KORAMANGALA", "maxCapacity": 5 }
     * Both are replaced: null zone = unknown, null capacity = fleet default.
     */
    @PatchMapping("/{id}")
    public AgentView updateAgent(@PathVariable String id, @RequestBody UpdateAgentRequest request) {
        return AgentView.from(agentService.updateDetails(id, request.currentZone(), request.maxCapacity()));
    }

    /**
     * POST /agents/{id}/heartbeat - called by the agent's phone app every few seconds.
     * If heartbeats stop for agents.heartbeat.timeout-seconds, the agent is marked
     * OFFLINE automatically and their orders are re-planned.
     */
    @PostMapping("/{id}/heartbeat")
    public AgentView heartbeat(@PathVariable String id) {
        return AgentView.from(agentService.heartbeat(id));
    }

    public record CreateAgentRequest(@NotBlank String id, @NotBlank String name) {
    }

    public record UpdateStatusRequest(@NotBlank String status) {
    }

    public record UpdateAgentRequest(String currentZone, Integer maxCapacity) {
    }
}
