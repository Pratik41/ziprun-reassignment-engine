package com.ziprun.controller;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.service.agent.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Agent Controller: REST API for agent management.
 *
 * RESPONSIBILITY: Handle HTTP concerns only
 * - Parse request body
 * - Validate input (null checks)
 * - Call business logic (AgentService)
 * - Format response
 * - Return HTTP status codes
 *
 * DOES NOT: Query database directly, publish events, apply business rules
 *
 * Endpoints:
 * GET    /agents              - List agents
 * GET    /agents/{id}         - Get single agent
 * GET    /agents?status=...   - List agents by status
 * PATCH  /agents/{id}/status  - Update agent status (AVAILABLE, BUSY, OFFLINE)
 */
@RestController
@RequestMapping("/agents")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * POST /agents - Create a new agent.
     *
     * Request: { "id": "AGT-001", "name": "Agent Name" }
     * Response: 200 OK + created agent
     */
    @PostMapping
    public ResponseEntity<?> createAgent(@RequestBody CreateAgentRequest request) {
        log.debug("POST /agents: id={}, name={}", request.getId(), request.getName());

        if (request.getId() == null || request.getId().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Agent ID is required"));
        }
        if (request.getName() == null || request.getName().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Agent name is required"));
        }

        Agent agent = new Agent();
        agent.setId(request.getId());
        agent.setName(request.getName());
        agent.setStatus(AgentStatus.AVAILABLE);
        agent.setActiveOrderCount(0);

        return ResponseEntity.ok(agentService.save(agent));
    }

    /**
     * GET /agents - List all agents.
     *
     * Response: 200 OK + list of agents
     */
    @GetMapping
    public ResponseEntity<List<Agent>> listAgents() {
        log.debug("GET /agents");
        return ResponseEntity.ok(agentService.findAll());
    }

    /**
     * GET /agents/{id} - Get single agent by ID.
     *
     * Response: 200 OK + agent details, or 404 Not Found
     */
    @GetMapping("/{id}")
    public ResponseEntity<Agent> getAgent(@PathVariable String id) {
        log.debug("GET /agents/{}: id={}", id, id);

        return agentService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    log.warn("Agent not found: {}", id);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * GET /agents?status=... - List agents by status.
     *
     * Query params: ?status=AVAILABLE | BUSY | OFFLINE
     * Response: 200 OK + list of agents
     */
    @GetMapping(params = "status")
    public ResponseEntity<?> listAgentsByStatus(@RequestParam String status) {
        log.debug("GET /agents?status={}: status={}", status, status);

        try {
            AgentStatus agentStatus = AgentStatus.valueOf(status.toUpperCase());
            return ResponseEntity.ok(agentService.findByStatus(agentStatus));

        } catch (IllegalArgumentException e) {
            log.warn("Invalid agent status: {}", status);
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid status: " + status));
        }
    }

    /**
     * PATCH /agents/{id}/status - Update agent status.
     *
     * CRITICAL: When status changes to OFFLINE, an AgentOfflineEvent is published
     * to trigger the agentic re-planning loop. This is handled by AgentService.
     *
     * Request: { "status": "OFFLINE" }
     * Response: 200 OK + updated agent, or 400/404
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateAgentStatus(
            @PathVariable String id,
            @RequestBody UpdateAgentStatusRequest request
    ) {
        log.debug("PATCH /agents/{}/status: newStatus={}", id, request.getStatus());

        if (request.getStatus() == null || request.getStatus().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Status is required"));
        }

        try {
            AgentStatus newStatus = AgentStatus.valueOf(request.getStatus().toUpperCase());
            Agent updated = agentService.updateStatus(id, newStatus);

            log.info("Agent status updated: id={}, status={} (check for agentic loop)", id, newStatus);
            return ResponseEntity.ok(updated);

        } catch (IllegalArgumentException e) {
            log.warn("Failed to update agent status: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ============ DTOs ============

    public static class CreateAgentRequest {
        private String id;
        private String name;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class UpdateAgentStatusRequest {
        private String status;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class ErrorResponse {
        private String message;

        public ErrorResponse(String message) { this.message = message; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
