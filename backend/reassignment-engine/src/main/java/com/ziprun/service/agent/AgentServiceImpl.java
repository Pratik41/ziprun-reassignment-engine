package com.ziprun.service.agent;

import com.ziprun.domain.Activity;
import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.event.AgentAvailableEvent;
import com.ziprun.domain.event.AgentBusyEvent;
import com.ziprun.domain.event.AgentOfflineEvent;
import com.ziprun.exception.InvalidStateException;
import com.ziprun.exception.NotFoundException;
import com.ziprun.repository.AgentRepository;
import com.ziprun.service.activity.ActivityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Agent Service Implementation: All agent business logic lives here.
 *
 * EVENT PUBLISHING (Agentic Loop Trigger):
 * Every availability change publishes an event (OFFLINE / BUSY / AVAILABLE).
 * The re-planning loop (ReplanEventHandler) handles it asynchronously after
 * this transaction commits. See ADR-4.
 *
 * Status changes come from two places: ops (updateStatus) and the heartbeat
 * monitor (markOfflineIfSilentSince). Both go through changeStatus so events
 * and the activity log behave the same.
 */
@Service
@Transactional
public class AgentServiceImpl implements AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String AUTO_OFFLINE_PREFIX = "Auto-offline";

    private final AgentRepository agentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ActivityService activity;

    public AgentServiceImpl(AgentRepository agentRepository, ApplicationEventPublisher eventPublisher,
                            ActivityService activity) {
        this.agentRepository = agentRepository;
        this.eventPublisher = eventPublisher;
        this.activity = activity;
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
        Agent agent = getAgent(agentId);

        if (agent.getStatus() == AgentStatus.AVAILABLE && newStatus != AgentStatus.AVAILABLE) {
            ensureAnotherAgentStaysAvailable(agent, newStatus);
        }
        AgentStatus oldStatus = agent.getStatus();
        if (oldStatus == newStatus) {
            return agent;
        }

        // A manual decision wins: clear the automatic note and pause heartbeat monitoring
        // until the app reports again (otherwise a stale heartbeat would flip them straight back)
        agent.setStatusNote(null);
        agent.setLastHeartbeatAt(null);
        changeStatus(agent, newStatus);
        activity.record(Activity.Type.AGENT_STATUS, Activity.Actor.OPS,
            String.format("%s: %s → %s", agent.getName(), label(oldStatus), label(newStatus)), null, agentId, null);
        return agent;
    }

    @Override
    public Agent heartbeat(String agentId) {
        Agent agent = getAgent(agentId);
        LocalDateTime now = LocalDateTime.now();
        agent.setLastHeartbeatAt(now);
        if (agent.getStatus() == AgentStatus.OFFLINE && agent.getStatusNote() != null
                && agent.getStatusNote().startsWith(AUTO_OFFLINE_PREFIX)) {
            // Their app is back, but whether they're back on shift is ops' call
            agent.setStatusNote("App reconnected at " + now.format(TIME) + ". Set Available when they're back on shift.");
        }
        return agent;
    }

    @Override
    public boolean markOfflineIfSilentSince(String agentId, LocalDateTime cutoff) {
        Agent agent = getAgent(agentId);
        LocalDateTime last = agent.getLastHeartbeatAt();
        // Re-check under this transaction: a heartbeat may have arrived, or ops may have changed status
        if (agent.getStatus() == AgentStatus.OFFLINE || last == null || !last.isBefore(cutoff)) {
            return false;
        }
        AgentStatus oldStatus = agent.getStatus();
        agent.setStatusNote(AUTO_OFFLINE_PREFIX + ": no heartbeat from their app since " + last.format(TIME));
        changeStatus(agent, AgentStatus.OFFLINE);
        activity.record(Activity.Type.AGENT_AUTO_OFFLINE, Activity.Actor.SYSTEM,
            String.format("%s marked Offline (was %s): their app stopped sending heartbeats at %s",
                agent.getName(), label(oldStatus), last.format(TIME)), null, agentId, null);
        log.warn("Agent {} ({}) marked OFFLINE: no heartbeat since {}", agentId, agent.getName(), last);
        return true;
    }

    /**
     * Applies the change and publishes the availability event for the re-planning loop.
     * The listener runs AFTER this transaction commits, on another thread, so callers
     * return immediately and the listener sees the committed status.
     */
    private void changeStatus(Agent agent, AgentStatus newStatus) {
        AgentStatus oldStatus = agent.getStatus();
        agent.setStatus(newStatus);

        if (newStatus == AgentStatus.OFFLINE && oldStatus != AgentStatus.OFFLINE) {
            log.info("Agent going OFFLINE: id={}, name={}. Publishing AgentOfflineEvent.", agent.getId(), agent.getName());
            eventPublisher.publishEvent(new AgentOfflineEvent(this, agent.getId(), agent.getName()));
        } else if (oldStatus == AgentStatus.AVAILABLE && newStatus == AgentStatus.BUSY) {
            // Still delivering their own orders, but no longer a valid recommendation
            eventPublisher.publishEvent(new AgentBusyEvent(this, agent.getId(), agent.getName()));
        } else if (newStatus == AgentStatus.AVAILABLE && oldStatus != AgentStatus.AVAILABLE) {
            // More capacity: re-balance suggestions for stranded orders
            eventPublisher.publishEvent(new AgentAvailableEvent(this, agent.getId(), agent.getName()));
        }
        log.info("Agent status updated: id={}, oldStatus={}, newStatus={}", agent.getId(), oldStatus, newStatus);
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

    private Agent getAgent(String agentId) {
        return agentRepository.findById(agentId).orElseThrow(() -> new NotFoundException("Agent", agentId));
    }

    private static String label(AgentStatus status) {
        return status.name().charAt(0) + status.name().substring(1).toLowerCase();
    }
}
