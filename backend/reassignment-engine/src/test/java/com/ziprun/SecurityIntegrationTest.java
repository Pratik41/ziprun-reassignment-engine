package com.ziprun;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in, CSRF and the agent-app token, with security on (as in production).
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:security-${random.uuid}",
    "routing.strategy=rule-based",
    "llm.providers=mock",
    "ops.username=dispatcher",
    "ops.password=s3cret-pass",
    "agents.app-token=phone-app-token"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SecurityIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void everythingButSignInNeedsAUser() throws Exception {
        mvc.perform(get("/agents")).andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)); // no browser login prompt
        mvc.perform(get("/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void signInGivesASessionThatWorksForReadsAndCsrfProtectedWrites() throws Exception {
        MockHttpSession session = signIn("dispatcher", "s3cret-pass");

        mvc.perform(get("/auth/me").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("dispatcher"));
        MvcResult read = mvc.perform(get("/agents").session(session)).andExpect(status().isOk()).andReturn();
        Cookie xsrf = read.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();

        // a write without the token (what a forged cross-site request looks like) is refused...
        mvc.perform(patch("/agents/AGT-002").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentZone\":\"KORAMANGALA\",\"maxCapacity\":4}"))
            .andExpect(status().isForbidden());
        // ...and accepted when the console echoes the cookie in the header
        mvc.perform(patch("/agents/AGT-002").session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentZone\":\"KORAMANGALA\",\"maxCapacity\":4}"))
            .andExpect(status().isOk());

        mvc.perform(post("/auth/logout").session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/agents").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsRefusedAndRepeatedFailuresAreSlowedDown() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"dispatcher\",\"password\":\"guess" + i + "\"}"))
                .andExpect(status().isUnauthorized());
        }
        // even the right password is refused while locked out
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"dispatcher\",\"password\":\"s3cret-pass\"}"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void basicAuthWorksForScripts() throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString("dispatcher:s3cret-pass".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/orders").header(HttpHeaders.AUTHORIZATION, basic)).andExpect(status().isOk());
        mvc.perform(post("/routing/recommend").header(HttpHeaders.AUTHORIZATION, basic)).andExpect(status().isOk());
    }

    @Test
    void phoneAppTokenCanSendHeartbeatsButNothingElse() throws Exception {
        mvc.perform(post("/agents/AGT-002/heartbeat").header("X-Agent-Token", "phone-app-token")).andExpect(status().isOk());
        mvc.perform(post("/agents/AGT-002/heartbeat").header("X-Agent-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/agents").header("X-Agent-Token", "phone-app-token")).andExpect(status().isUnauthorized());
    }

    private MockHttpSession signIn(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(username))
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
