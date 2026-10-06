package com.ziprun;

import com.ziprun.domain.Agent;
import com.ziprun.domain.AgentStatus;
import com.ziprun.domain.OrderStatus;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Heartbeat monitor with a 1-second timeout so the test runs quickly.
 * Seed: Rahul (AGT-002) and Kiran (AGT-004) AVAILABLE; Priya (AGT-001) BUSY with 3 orders.
 */
@SpringBootTest(properties = {
    "security.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:heartbeat-${random.uuid}",
    "llm.providers=mock",
    "agents.heartbeat.timeout-seconds=1",
    "agents.heartbeat.check-interval-ms=200"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HeartbeatIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AgentRepository agents;
    @Autowired OrderRepository orders;

    @Test
    void agentWhoseAppGoesQuietIsMarkedOfflineAndTheirOrdersReplanned() throws Exception {
        mvc.perform(post("/agents/AGT-001/heartbeat")).andExpect(status().isOk())
            .andExpect(jsonPath("$.lastHeartbeatAt").exists());

        Agent priya = awaitStatus("AGT-001", AgentStatus.OFFLINE);

        assertThat(priya.getStatusNote()).startsWith("Auto-offline: no heartbeat");
        mvc.perform(get("/activity")).andExpect(jsonPath("$[?(@.type == 'AGENT_AUTO_OFFLINE')]").exists());

        // the normal re-planning loop runs in the background after the status commit:
        // wait for it to flag her orders as waiting for a new agent
        long deadline = System.currentTimeMillis() + 8_000;
        int waiting = orders.findByStatus(OrderStatus.REASSIGNMENT_PENDING).size();
        while (waiting < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            waiting = orders.findByStatus(OrderStatus.REASSIGNMENT_PENDING).size();
        }
        assertThat(waiting).isEqualTo(3);
    }

    @Test
    void steadyHeartbeatsKeepTheAgentOnDuty() throws Exception {
        for (int i = 0; i < 15; i++) {
            mvc.perform(post("/agents/AGT-002/heartbeat")).andExpect(status().isOk());
            Thread.sleep(150); // well inside the 1s timeout, even on a slow CI machine
        }
        assertThat(agents.findById("AGT-002").orElseThrow().getStatus()).isEqualTo(AgentStatus.AVAILABLE);
    }

    @Test
    void agentsWithoutAnAppAreNeverAutoOfflined() throws Exception {
        Thread.sleep(1500);
        assertThat(agents.findById("AGT-004").orElseThrow().getStatus()).isEqualTo(AgentStatus.AVAILABLE);
    }

    @Test
    void manualStatusChangePausesMonitoringUntilTheAppReportsAgain() throws Exception {
        mvc.perform(post("/agents/AGT-001/heartbeat")).andExpect(status().isOk());
        awaitStatus("AGT-001", AgentStatus.OFFLINE);

        // the app reconnects: noted, but they stay OFFLINE until ops decides
        mvc.perform(post("/agents/AGT-001/heartbeat")).andExpect(jsonPath("$.statusNote").value(
            org.hamcrest.Matchers.startsWith("App reconnected")));
        assertThat(agents.findById("AGT-001").orElseThrow().getStatus()).isEqualTo(AgentStatus.OFFLINE);

        // ops brings them back: the stale heartbeat must not flip them straight back to OFFLINE
        mvc.perform(patch("/agents/AGT-001/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.statusNote").doesNotExist());
        Thread.sleep(1500);
        assertThat(agents.findById("AGT-001").orElseThrow().getStatus()).isEqualTo(AgentStatus.AVAILABLE);
    }

    private Agent awaitStatus(String id, AgentStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8_000;
        Agent agent = agents.findById(id).orElseThrow();
        while (agent.getStatus() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            agent = agents.findById(id).orElseThrow();
        }
        assertThat(agent.getStatus()).as("status of %s", id).isEqualTo(expected);
        return agent;
    }
}
