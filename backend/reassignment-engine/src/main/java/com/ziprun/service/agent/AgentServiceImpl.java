package com.ziprun.service.agent;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.event.AgentOfflineEvent;
import com.ziprun.exception.InvalidStateException;
import com.ziprun.exception.NotFoundException;
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
 * EVENT PUBLISHING (Agentic Loop Trigger):
 * When agent status changes to OFFLINE, an AgentOfflineEvent is published.
 * This decouples the status update (this request) from re-planning
 * (ReplanEventHandler, async, after commit). See ADR-4.
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
    @Transactional(readOnly = true)
    public Optional<Agent> findById(String agentId) {
        return agentRepository.findById(agentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Agent> findAll() {
        return agentRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Agent> findByStatus(AgentStatus status) {
        return agentRepository.findByStatus(status);
    }

    @Override
    public Agent updateStatus(String agentId, AgentStatus newStatus) {
        log.debug("Updating agent status: id={}, newStatus={}", agentId, newStatus);

        Agent agent = agentRepository.findById(agentId)
            .orElseThrow(() -> new NotFoundException("Agent", agentId));

        AgentStatus oldStatus = agent.getStatus();
        if (oldStatus == AgentStatus.AVAILABLE && newStatus != AgentStatus.AVAILABLE) {
            ensureAnotherAgentStaysAvailable(agent, newStatus);
        }
        agent.setStatus(newStatus);

        // Critical: Publish event if status changes to OFFLINE.
        // The listener runs AFTER this transaction commits, on another thread,
        // so this request returns immediately and the listener sees the committed status.
        if (newStatus == AgentStatus.OFFLINE && oldStatus != AgentStatus.OFFLINE) {
            log.info("Agent going OFFLINE: id={}, name={}. Publishing AgentOfflineEvent.", agentId, agent.getName());
            eventPublisher.publishEvent(new AgentOfflineEvent(this, agentId, agent.getName()));
        }

        log.info("Agent status updated: id={}, oldStatus={}, newStatus={}", agentId, oldStatus, newStatus);
        return agent;
    }

    /**
     * Fleet rule: at least one agent must stay AVAILABLE, otherwise routing has no
     * candidates and stranded orders can't get suggestions. Taking the last
     * AVAILABLE agent to BUSY/OFFLINE is refused until someone else is AVAILABLE.
     * The AVAILABLE rows are locked so concurrent requests can't both pass.
     */
    private void ensureAnotherAgentStaysAvailable(Agent agent, AgentStatus newStatus) {
        boolean anotherAvailable = agentRepository.findByStatusForUpdate(AgentStatus.AVAILABLE).stream()
            .anyMatch(other -> !other.getId().equals(agent.getId()));
        if (!anotherAvailable) {
            throw new InvalidStateException(String.format(
                "%s is the only AVAILABLE agent and can't be set to %s. Make another agent AVAILABLE first.",
                agent.getName(), newStatus));
        }
    }

    @Override
    public Agent save(Agent agent) {
        if (agentRepository.existsById(agent.getId())) {
            throw new InvalidStateException("Agent already exists: " + agent.getId());
        }
        log.debug("Saving agent: id={}, name={}", agent.getId(), agent.getName());
        return agentRepository.save(agent);
    }
}
