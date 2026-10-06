package com.ziprun;

import com.ziprun.domain.Order;
import com.ziprun.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /routing/recommend for the "New order" dialog, and recording whether ops followed it.
 * Seed: Rahul (AGT-002) and Kiran (AGT-004) are the only AVAILABLE agents, both with 0 orders.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:recommend-${random.uuid}",
    "routing.strategy=rule-based",
    "llm.providers=mock"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class NewOrderRecommendationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;

    @Test
    void recommendsTheLighterLoadedAgentFirstAndSavesNothing() throws Exception {
        createOrder("Warm-up", "AGT-002", null); // Rahul now carries 1, Kiran 0
        long before = orders.count();

        mvc.perform(post("/routing/recommend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Groceries - Koramangala to HSR\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.strategy").value("rule-based"))
            .andExpect(jsonPath("$.options.length()").value(2))
            .andExpect(jsonPath("$.options[0].recommendedAgentId").value("AGT-004"))
            .andExpect(jsonPath("$.options[1].recommendedAgentId").value("AGT-002"))
            .andExpect(jsonPath("$.options[0].reasoning").isNotEmpty());

        assertThat(orders.count()).isEqualTo(before);
    }

    @Test
    void worksWithTheAiStrategyAndNoDescriptionYet() throws Exception {
        mvc.perform(put("/routing/strategy").contentType(MediaType.APPLICATION_JSON).content("{\"strategy\":\"ai\"}"))
            .andExpect(status().isOk());

        mvc.perform(post("/routing/recommend"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.strategy").value("ai"))
            .andExpect(jsonPath("$.options[0].source").value(startsWith("ai:")))
            .andExpect(jsonPath("$.options[0].recommendedAgentId").value(org.hamcrest.Matchers.oneOf("AGT-002", "AGT-004")));
    }

    @Test
    void recordsWhetherTheRecommendedAgentWasPicked() throws Exception {
        String followed = createOrder("Documents", "AGT-004", "AGT-004");
        String overridden = createOrder("Flowers", "AGT-002", "AGT-004");
        createOrder("No recommendation shown", "AGT-002", null);

        Order a = orders.findById(followed).orElseThrow();
        Order b = orders.findById(overridden).orElseThrow();
        assertThat(a.getFollowedRecommendation()).isTrue();
        assertThat(b.getFollowedRecommendation()).isFalse();
        assertThat(b.getRecommendedAgentId()).isEqualTo("AGT-004");

        mvc.perform(get("/metrics"))
            .andExpect(jsonPath("$.newOrderPicks.recommended").value(2))
            .andExpect(jsonPath("$.newOrderPicks.followed").value(1))
            .andExpect(jsonPath("$.newOrderPicks.followRate").value(0.5));
        assertThat(mvc.perform(get("/activity")).andReturn().getResponse().getContentAsString())
            .contains("(recommended pick)", "(recommendation was Kiran Nair)");
    }

    private String createOrder(String description, String agentId, String recommendedAgentId) throws Exception {
        String body = String.format("{\"description\":\"%s\",\"assignedAgentId\":\"%s\"%s}", description, agentId,
            recommendedAgentId == null ? "" : ",\"recommendedAgentId\":\"" + recommendedAgentId + "\"");
        String response = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.id");
    }
}
