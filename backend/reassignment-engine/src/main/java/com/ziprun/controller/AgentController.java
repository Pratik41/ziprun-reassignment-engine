package com.ziprun.controller;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.repository.AgentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.ziprun.domain.event.AgentOfflineEvent;

import java.util.List;


/**
 * REST API for agent management.
 *
 * Endpoints:
 * GET /agents - List all agents
 * GET /agents/{id} - Get single agent
 * PATCH /agents/{id}/status - Update agent status (FIRES AGENTIC LOOP if status=OFFLINE)
 *
 * Key design: When status changes to OFFLINE, publish AgentOfflineEvent
 * which triggers async re-planning (see ADR-4)
 */
@RestController
@RequestMapping("/agents")
public class AgentController {

    private final AgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AgentController(AgentRepository agentRepository, ApplicationEventPublisher eventPublisher) {
        this.agentRepository = agentRepository;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping
    public ResponseEntity<List<Agent>> listAgents() {
        return ResponseEntity.ok(agentRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Agent> getAgent(@PathVariable String id) {
        return agentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Agent> updateAgentStatus(
            @PathVariable String id,
            @RequestBody UpdateAgentStatusRequest request
    ) {
        return agentRepository.findById(id)
                .map(agent -> {
                    try {
                        AgentStatus newStatus = AgentStatus.valueOf(request.getStatus().toUpperCase());
                        AgentStatus oldStatus = agent.getStatus();
                        agent.setStatus(newStatus);

                        Agent saved = agentRepository.save(agent);

                        // CRITICAL: Fire event if status changed to OFFLINE
                        // This triggers async re-planning without blocking this response
                        if (newStatus == AgentStatus.OFFLINE && oldStatus != AgentStatus.OFFLINE) {
                            eventPublisher.publishEvent(
                                    new AgentOfflineEvent(this, agent.getId(), agent.getName())
                            );
                        }

                        return ResponseEntity.ok(saved);
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.badRequest().<Agent>build();
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // DTO Class
    public static class UpdateAgentStatusRequest {
        private String status;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
