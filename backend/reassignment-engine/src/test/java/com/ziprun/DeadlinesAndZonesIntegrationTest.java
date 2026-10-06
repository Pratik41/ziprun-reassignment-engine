package com.ziprun;

import com.jayway.jsonpath.JsonPath;
import com.ziprun.domain.Order;
import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.ReassignmentSuggestion;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.domain.TriggerReason;
import com.ziprun.repository.OrderRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import com.ziprun.service.order.SlaMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delivery deadlines (SLA monitor), zones and agent capacity through the API.
 * Seed (V2 + V3): Rahul (AGT-002, HSR Layout) and Kiran (AGT-004, Malleshwaram) are the
 * only AVAILABLE agents, both with 0 orders. The monitor is invoked directly here.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sla-${random.uuid}",
    "routing.strategy=rule-based",
    "llm.providers=mock",
    "orders.sla.check-interval-ms=3600000"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DeadlinesAndZonesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired ReassignmentSuggestionRepository suggestions;
    @Autowired SlaMonitor slaMonitor;

    @Test
    void ordersGetZonesAndADeadline() throws Exception {
        String id = createOrder("{\"description\":\"Cake\",\"assignedAgentId\":\"AGT-002\","
            + "\"pickupZone\":\"KORAMANGALA\",\"dropoffZone\":\"INDIRANAGAR\",\"slaMinutes\":45}");
        Order order = orders.findById(id).orElseThrow();

        assertThat(order.getPickupZone()).isEqualTo("KORAMANGALA");
        assertThat(Duration.between(order.getCreatedAt(), order.getSlaDeadline())).isEqualTo(Duration.ofMinutes(45));

        String noDeadline = createOrder("{\"description\":\"Letter\",\"assignedAgentId\":\"AGT-002\",\"slaMinutes\":0}");
        assertThat(orders.findById(noDeadline).orElseThrow().getSlaDeadline()).isNull();
        String byDefault = createOrder("{\"description\":\"Shoes\",\"assignedAgentId\":\"AGT-002\"}");
        Order defaulted = orders.findById(byDefault).orElseThrow();
        assertThat(Duration.between(defaulted.getCreatedAt(), defaulted.getSlaDeadline())).isEqualTo(Duration.ofMinutes(120));

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\",\"assignedAgentId\":\"AGT-002\",\"pickupZone\":\"ATLANTIS\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ATLANTIS")));
    }

    @Test
    void atRiskOrderGetsASuggestionForAFasterAgentOnce() throws Exception {
        // Rahul carries 3 orders; the last one is due in 20 minutes (inside the 30-minute window)
        createOrder("{\"description\":\"A\",\"assignedAgentId\":\"AGT-002\"}");
        createOrder("{\"description\":\"B\",\"assignedAgentId\":\"AGT-002\"}");
        String urgent = createOrder("{\"description\":\"Medicine\",\"assignedAgentId\":\"AGT-002\",\"slaMinutes\":20}");

        slaMonitor.checkDeadlines();
        slaMonitor.checkDeadlines(); // flagged once only

        List<ReassignmentSuggestion> open = suggestions.findByOrderIdAndStatus(urgent, SuggestionStatus.PENDING);
        assertThat(open).singleElement().satisfies(s -> {
            assertThat(s.getTriggerReason()).isEqualTo(TriggerReason.SLA_RISK);
            assertThat(s.getRecommendedAgentId()).isEqualTo("AGT-004");
            assertThat(s.getReasoning()).startsWith("Deadline at risk");
        });
        assertThat(orders.findById(urgent).orElseThrow().getSlaAlertedAt()).isNotNull();
        assertThat(mvc.perform(get("/activity")).andReturn().getResponse().getContentAsString())
            .contains("at risk of missing its deadline", "suggested moving it to Kiran Nair");

        // Ops accepts: the order moves while it is still ASSIGNED (no stranding involved)
        mvc.perform(patch("/suggestions/" + open.get(0).getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACCEPTED\"}"))
            .andExpect(status().isOk());
        Order moved = orders.findById(urgent).orElseThrow();
        assertThat(moved.getStatus()).isEqualTo(OrderStatus.REASSIGNED);
        assertThat(moved.getAssignedAgentId()).isEqualTo("AGT-004");
    }

    @Test
    void atRiskOrderStaysWhenNobodyIsFaster() throws Exception {
        String urgent = createOrder("{\"description\":\"Only order\",\"assignedAgentId\":\"AGT-002\",\"slaMinutes\":10}");

        slaMonitor.checkDeadlines();

        assertThat(suggestions.findByOrderIdAndStatus(urgent, SuggestionStatus.PENDING)).isEmpty();
        assertThat(mvc.perform(get("/activity")).andReturn().getResponse().getContentAsString())
            .contains("no agent could start it sooner; it stays with Rahul Verma");
    }

    @Test
    void deliveringAnOrderWithdrawsItsOpenSuggestion() throws Exception {
        createOrder("{\"description\":\"A\",\"assignedAgentId\":\"AGT-002\"}");
        createOrder("{\"description\":\"B\",\"assignedAgentId\":\"AGT-002\"}");
        String urgent = createOrder("{\"description\":\"C\",\"assignedAgentId\":\"AGT-002\",\"slaMinutes\":5}");
        slaMonitor.checkDeadlines();

        mvc.perform(patch("/orders/" + urgent + "/status").contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"DELIVERED\"}")).andExpect(status().isOk());

        assertThat(suggestions.findByOrderId(urgent)).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SuggestionStatus.EXPIRED));
    }

    @Test
    void opsSetsZoneAndCapacityAndRoutingRespectsBoth() throws Exception {
        mvc.perform(patch("/agents/AGT-004").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentZone\":\"KORAMANGALA\",\"maxCapacity\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentZone").value("KORAMANGALA"))
            .andExpect(jsonPath("$.maxCapacity").value(1));

        // Kiran is now in the pickup zone, so he wins over Rahul (HSR Layout, a neighbour)
        mvc.perform(post("/routing/recommend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Cake\",\"pickupZone\":\"KORAMANGALA\"}"))
            .andExpect(jsonPath("$.options[0].recommendedAgentId").value("AGT-004"));

        // ...until he's at his capacity of 1
        createOrder("{\"description\":\"Flowers\",\"assignedAgentId\":\"AGT-004\"}");
        mvc.perform(post("/routing/recommend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Cake\",\"pickupZone\":\"KORAMANGALA\"}"))
            .andExpect(jsonPath("$.options.length()").value(1))
            .andExpect(jsonPath("$.options[0].recommendedAgentId").value("AGT-002"))
            .andExpect(jsonPath("$.options[0].reasoning").value(org.hamcrest.Matchers.containsString("Kiran Nair 1/1")));

        mvc.perform(patch("/agents/AGT-004").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentZone\":\"ATLANTIS\",\"maxCapacity\":null}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void configListsZonesAndDefaults() throws Exception {
        mvc.perform(get("/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.defaultMaxCapacity").value(6))
            .andExpect(jsonPath("$.defaultSlaMinutes").value(120))
            .andExpect(jsonPath("$.zones[?(@.id == 'KORAMANGALA')].neighbours[*]").value(
                org.hamcrest.Matchers.hasItem("HSR_LAYOUT")));
    }

    private String createOrder(String json) throws Exception {
        String body = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
