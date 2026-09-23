package com.ziprun.service.agent;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.event.AgentOfflineEvent;
import com.ziprun.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Agent Service Implementation: All agent business logic lives here.
 *
 * Key responsibilities:
 * - Query agents for routing and UI
 * - Update agent status with event publishing
 * - Manage agent workload (order counts)
 *
 * EVENT PUBLISHING (Design Pattern - Agentic Loop Trigger):
 * When agent status changes to OFFLINE, an AgentOfflineEvent is published.
 * This decouples agent status update (controller/service) from re-planning logic
 * (event handler in T-4). Follows principle in ADR-4.
 *
 * Controller → AgentService → Repository (clean separation)
 * When OFFLINE → AgentService → ApplicationEventPublisher → ReplanEventHandler
 */
@Service
@Transactional
public class AgentServiceImpl implements AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private final AgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AgentServiceImpl(AgentRepository agentRepository, ApplicationEventPublisher eventPublisher) {
        this.agentRepository = agentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Optional<Agent> findById(String agentId) {
        log.debug("Fetching agent: {}", agentId);
        return agentRepository.findById(agentId);
    }

    @Override
    public List<Agent> findAll() {
        log.debug("Fetching all agents");
        return agentRepository.findAll();
    }

    @Override
    public List<Agent> findByStatus(AgentStatus status) {
        log.debug("Fetching agents by status: {}", status);
        return agentRepository.findByStatus(status);
    }

    @Override
    public Agent updateStatus(String agentId, AgentStatus newStatus) {
        log.debug("Updating agent status: id={}, newStatus={}", agentId, newStatus);

        Agent agent = agentRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        AgentStatus oldStatus = agent.getStatus();
        agent.setStatus(newStatus);
        Agent updated = agentRepository.save(agent);

        // Critical: Publish event if status changes to OFFLINE
        // This triggers agentic re-planning loop without blocking this request
        if (newStatus == AgentStatus.OFFLINE && oldStatus != AgentStatus.OFFLINE) {
            log.info("Agent going OFFLINE: id={}, name={}. Publishing AgentOfflineEvent.", agentId, agent.getName());
            eventPublisher.publishEvent(new AgentOfflineEvent(this, agentId, agent.getName()));
        }

        log.info("Agent status updated: id={}, oldStatus={}, newStatus={}", agentId, oldStatus, newStatus);
        return updated;
    }

    @Override
    public void incrementOrderCount(String agentId) {
        Agent agent = agentRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        agent.setActiveOrderCount(agent.getActiveOrderCount() + 1);
        agentRepository.save(agent);

        log.debug("Agent order count incremented: id={}, newCount={}", agentId, agent.getActiveOrderCount());
    }

    @Override
    public void decrementOrderCount(String agentId) {
        Agent agent = agentRepository.findById(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        int newCount = Math.max(0, agent.getActiveOrderCount() - 1);
        agent.setActiveOrderCount(newCount);
        agentRepository.save(agent);

        log.debug("Agent order count decremented: id={}, newCount={}", agentId, newCount);
    }
}
