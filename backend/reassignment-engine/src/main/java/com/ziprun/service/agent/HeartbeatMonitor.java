package com.ziprun.service.agent;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * Notices agents whose phone app has stopped reporting and marks them OFFLINE,
 * which fires the normal re-planning loop. This is the "observe" step without a
 * human: nobody has to click Offline.
 *
 * Only agents whose app has sent at least one heartbeat are monitored (null
 * lastHeartbeatAt = not tracked), so agents managed purely by hand are unaffected.
 * A scheduled check is right here because the event *is* time passing.
 */
@Component
public class HeartbeatMonitor {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final Duration timeout;

    public HeartbeatMonitor(AgentRepository agentRepository, AgentService agentService,
                            @Value("${agents.heartbeat.timeout-seconds:60}") long timeoutSeconds) {
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Scheduled(fixedDelayString = "${agents.heartbeat.check-interval-ms:5000}",
               initialDelayString = "${agents.heartbeat.check-interval-ms:5000}")
    public void checkHeartbeats() {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeout);
        for (Agent agent : agentRepository.findByStatusInAndLastHeartbeatAtBefore(
                EnumSet.of(AgentStatus.AVAILABLE, AgentStatus.BUSY), cutoff)) {
            try {
                agentService.markOfflineIfSilentSince(agent.getId(), cutoff);
            } catch (Exception e) {
                log.error("Heartbeat check failed for agent {}", agent.getId(), e);
            }
        }
    }

    public Duration getTimeout() {
        return timeout;
    }
}
