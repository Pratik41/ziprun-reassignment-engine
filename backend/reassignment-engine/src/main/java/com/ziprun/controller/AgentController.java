package com.ziprun.controller;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.exception.NotFoundException;
import com.ziprun.service.agent.AgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    public Agent createAgent(@Valid @RequestBody CreateAgentRequest request) {
        Agent agent = new Agent();
        agent.setId(request.id());
        agent.setName(request.name());
        agent.setStatus(AgentStatus.AVAILABLE);
        agent.setActiveOrderCount(0);
        return agentService.save(agent);
    }

    @GetMapping
    public List<Agent> listAgents(@RequestParam(name = "status", required = false) String status) {
        if (status == null || status.isBlank()) {
            return agentService.findAll();
        }
        return agentService.findByStatus(EnumParam.parse(AgentStatus.class, status, "agent status"));
    }

    @GetMapping("/{id}")
    public Agent getAgent(@PathVariable String id) {
        return agentService.findById(id).orElseThrow(() -> new NotFoundException("Agent", id));
    }

    /**
     * PATCH /agents/{id}/status - { "status": "OFFLINE" }
     */
    @PatchMapping("/{id}/status")
    public Agent updateAgentStatus(@PathVariable String id, @Valid @RequestBody UpdateStatusRequest request) {
        return agentService.updateStatus(id, EnumParam.parse(AgentStatus.class, request.status(), "agent status"));
    }

    public record CreateAgentRequest(@NotBlank String id, @NotBlank String name) {
    }

    public record UpdateStatusRequest(@NotBlank String status) {
    }
}
