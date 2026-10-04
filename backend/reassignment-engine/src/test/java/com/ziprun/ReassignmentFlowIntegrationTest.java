package com.ziprun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ziprun.domain.Agent;
import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.repository.AgentRepository;
import com.ziprun.repository.OrderRepository;
import com.ziprun.exception.StaleRecommendationException;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.routing.RoutingResult;
import com.ziprun.service.suggestion.SuggestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through HTTP, the async agentic loop and H2, using the mock LLM.
 * Seed (data.sql): AGT-001 Priya has ORD-001/002/008; AGT-002 and AGT-004 are the only AVAILABLE agents.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:flow-${random.uuid}",
    "routing.strategy=ai",
    "llm.providers=mock"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReassignmentFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OrderRepository orders;
    @Autowired AgentRepository agents;
    @Autowired ReassignmentSuggestionRepository suggestions;
    @Autowired SuggestionService suggestionService;

    @Test
    void agentOfflineQueuesOneSpreadOutSuggestionPerStrandedOrder() throws Exception {
        setAgentStatus("AGT-001", "OFFLINE");

        List<ReassignmentSuggestion> queued = awaitPendingReplans(3);

        assertThat(queued).extracting(ReassignmentSuggestion::getOrderId)
            .containsExactlyInAnyOrder("ORD-001", "ORD-002", "ORD-008");
        assertThat(queued).allSatisfy(s -> {
            assertThat(s.getTriggerReason()).isEqualTo(TriggerReason.AGENT_OFFLINE);
            assertThat(s.getSource()).isEqualTo("ai:mock");
            assertThat(s.getRecommendedAgentId()).isIn("AGT-002", "AGT-004");
        });
        // pending load spreads the batch across both available agents
        assertThat(queued).extracting(ReassignmentSuggestion::getRecommendedAgentId).contains("AGT-002", "AGT-004");
        assertThat(orders.findByStatus(OrderStatus.REASSIGNMENT_PENDING)).hasSize(3);
        // checkpoint: nothing was reassigned automatically
        assertThat(orders.findById("ORD-001").orElseThrow().getAssignedAgentId()).isEqualTo("AGT-001");
    }

    @Test
    void secondOfflineTriggerDoesNotDuplicateSuggestions() throws Exception {
        setAgentStatus("AGT-001", "OFFLINE");
        awaitPendingReplans(3);

        setAgentStatus("AGT-001", "AVAILABLE");
        setAgentStatus("AGT-001", "OFFLINE");
        Thread.sleep(1000); // give the second async run time to (not) do anything

        assertThat(pendingReplans()).hasSize(3);
    }

    @Test
    void acceptingReassignsOrderAndUpdatesLoads() throws Exception {
        setAgentStatus("AGT-001", "OFFLINE");
        ReassignmentSuggestion s = awaitPendingReplans(3).stream()
            .filter(x -> x.getOrderId().equals("ORD-001")).findFirst().orElseThrow();
        int newAgentLoadBefore = agents.findById(s.getRecommendedAgentId()).orElseThrow().getActiveOrderCount();

        mvc.perform(patch("/suggestions/" + s.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"accepted\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"));

        Order order = orders.findById("ORD-001").orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REASSIGNED);
        assertThat(order.getAssignedAgentId()).isEqualTo(s.getRecommendedAgentId());
        assertThat(agents.findById("AGT-001").orElseThrow().getActiveOrderCount()).isEqualTo(2);
        assertThat(agents.findById(s.getRecommendedAgentId()).orElseThrow().getActiveOrderCount())
            .isEqualTo(newAgentLoadBefore + 1);

        // deciding twice is a conflict, with the structured error shape
        mvc.perform(patch("/suggestions/" + s.getId()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REJECTED\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void acceptingOtherSuggestionRejectsSiblings() throws Exception {
        String first = createSuggestion("ORD-004");
        String second = createSuggestion("ORD-004");

        mvc.perform(patch("/suggestions/" + second).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}"))
            .andExpect(status().isOk());

        assertThat(suggestions.findById(first).orElseThrow().getStatus()).isEqualTo(SuggestionStatus.REJECTED);
        assertThat(orders.findById("ORD-004").orElseThrow().getStatus()).isEqualTo(OrderStatus.REASSIGNED);
    }

    @Test
    void strategyCanBeSwitchedAtRuntime() throws Exception {
        mvc.perform(get("/routing/strategy")).andExpect(jsonPath("$.active").value("ai"));

        mvc.perform(put("/routing/strategy").contentType(MediaType.APPLICATION_JSON).content("{\"strategy\":\"rule-based\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value("rule-based"));

        mvc.perform(post("/orders/ORD-003/suggest"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.source").value("rule-based"))
            .andExpect(jsonPath("$.triggerReason").value("INITIAL"));

        mvc.perform(put("/routing/strategy").contentType(MediaType.APPLICATION_JSON).content("{\"strategy\":\"zone\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Available")));
    }

    @Test
    void errorsComeBackStructured() throws Exception {
        mvc.perform(get("/orders").param("status", "lost"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Bad Request"))
            .andExpect(jsonPath("$.path").value("/orders"));
        mvc.perform(get("/orders/NOPE")).andExpect(status().isNotFound());
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details").isArray());
        mvc.perform(patch("/agents/AGT-002/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ASLEEP\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void suggestionsPointingAtAnAgentWhoGoesOfflineAreWithdrawnAndReplanned() throws Exception {
        // Priya's orders get suggestions spread across Rahul (AGT-002) and Kiran (AGT-004)
        setAgentStatus("AGT-001", "OFFLINE");
        List<ReassignmentSuggestion> first = awaitPendingReplans(3);
        List<String> toRahul = first.stream()
            .filter(s -> s.getRecommendedAgentId().equals("AGT-002")).map(ReassignmentSuggestion::getId).toList();
        assertThat(toRahul).isNotEmpty();

        // Now Rahul goes offline too: his recommendations are stale
        setAgentStatus("AGT-002", "OFFLINE");
        List<ReassignmentSuggestion> after = await(this::pendingReplans,
            list -> list.size() == 3 && list.stream().noneMatch(s -> s.getRecommendedAgentId().equals("AGT-002")));

        assertThat(toRahul).allSatisfy(id ->
            assertThat(suggestions.findById(id).orElseThrow().getStatus()).isEqualTo(SuggestionStatus.EXPIRED));
        assertThat(after).extracting(ReassignmentSuggestion::getRecommendedAgentId).containsOnly("AGT-004");
    }

    @Test
    void suggestionForAnAgentWhoWentOfflineMidRoutingIsRefused() throws Exception {
        // Routing picked Kiran, but Kiran went offline before the suggestion was saved
        RoutingResult picked = new RoutingResult("AGT-004", 0.9, "Kiran is free", "ai:gemini");
        setAgentStatus("AGT-004", "OFFLINE");

        assertThatThrownBy(() -> suggestionService.createSuggestion("ORD-003", picked, TriggerReason.INITIAL))
            .isInstanceOf(StaleRecommendationException.class);
        assertThat(suggestions.findByOrderId("ORD-003")).isEmpty();
    }

    @Test
    void lastAvailableAgentCannotGoBusyOrOfflineUntilAnotherIsAvailable() throws Exception {
        // Seed: Rahul (AGT-002) and Kiran (AGT-004) are the only AVAILABLE agents
        setAgentStatus("AGT-002", "BUSY");

        for (String status : List.of("OFFLINE", "BUSY")) {
            mvc.perform(patch("/agents/AGT-004/status").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("only AVAILABLE agent")));
        }
        assertThat(agents.findById("AGT-004").orElseThrow().getStatus().name()).isEqualTo("AVAILABLE");

        // Once someone else is AVAILABLE, it's allowed
        setAgentStatus("AGT-001", "AVAILABLE");
        setAgentStatus("AGT-004", "OFFLINE");
    }

    @Test
    void orderCanBeKeptWithItsAgentOnceTheyAreBack() throws Exception {
        setAgentStatus("AGT-001", "OFFLINE");
        awaitPendingReplans(3);

        // still offline: refused
        mvc.perform(post("/orders/ORD-001/keep")).andExpect(status().isConflict());

        setAgentStatus("AGT-001", "AVAILABLE");
        mvc.perform(post("/orders/ORD-001/keep"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ASSIGNED"))
            .andExpect(jsonPath("$.assignedAgentId").value("AGT-001"));

        assertThat(suggestions.findByOrderId("ORD-001")).extracting(ReassignmentSuggestion::getStatus)
            .containsOnly(SuggestionStatus.EXPIRED);
    }

    @Test
    void createOrderUpdatesAgentLoad() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Cake\",\"assignedAgentId\":\"AGT-002\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ASSIGNED"));

        assertThat(agents.findById("AGT-002").map(Agent::getActiveOrderCount)).contains(1);
    }

    // ---------- helpers ----------

    private void setAgentStatus(String agentId, String status) throws Exception {
        mvc.perform(patch("/agents/" + agentId + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"))
            .andExpect(status().isOk());
    }

    private String createSuggestion(String orderId) throws Exception {
        String body = mvc.perform(post("/orders/" + orderId + "/suggest"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("id").asText();
    }

    private List<ReassignmentSuggestion> pendingReplans() {
        return suggestions.findByStatus(SuggestionStatus.PENDING).stream()
            .filter(s -> s.getTriggerReason() == TriggerReason.AGENT_OFFLINE)
            .toList();
    }

    private List<ReassignmentSuggestion> awaitPendingReplans(int expected) throws InterruptedException {
        return await(() -> pendingReplans(), list -> list.size() >= expected);
    }

    private static <T> T await(Supplier<T> supplier, java.util.function.Predicate<T> done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        T value = supplier.get();
        while (!done.test(value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            value = supplier.get();
        }
        assertThat(done.test(value)).as("condition reached within 10s, last value: %s", value).isTrue();
        return value;
    }
}
