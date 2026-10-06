package com.ziprun;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /events pushes a change event after data is committed, naming what changed.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:live-${random.uuid}",
    "routing.strategy=rule-based",
    "llm.providers=mock"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LiveUpdatesIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void creatingAnOrderPushesAChangeEvent() throws Exception {
        MvcResult stream = mvc.perform(get("/events")).andExpect(request().asyncStarted()).andReturn();
        MockHttpServletResponse events = stream.getResponse();
        assertThat(events.getContentAsString()).contains("event:hello");

        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"Cake\",\"assignedAgentId\":\"AGT-002\"}")).andExpect(status().isCreated());

        String body = awaitContaining(events, "event:change");
        assertThat(body).contains("orders", "agents", "activity");
    }

    @Test
    void anAgentStatusChangeAndTheReplanThatFollowsArePushed() throws Exception {
        MockHttpServletResponse events = mvc.perform(get("/events")).andReturn().getResponse();

        mvc.perform(patch("/agents/AGT-001/status").contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"OFFLINE\"}")).andExpect(status().isOk());

        // the status change itself, then (async) the suggestions the re-plan queued
        assertThat(awaitContaining(events, "suggestions")).contains("event:change");
    }

    private static String awaitContaining(MockHttpServletResponse response, String text) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        String body = response.getContentAsString();
        while (!body.contains(text) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            body = response.getContentAsString();
        }
        assertThat(body).contains(text);
        return body;
    }
}
