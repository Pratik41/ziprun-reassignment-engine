package com.ziprun;

import com.ziprun.domain.OrderStatus;
import com.ziprun.domain.SuggestionStatus;
import com.ziprun.repository.OrderRepository;
import com.ziprun.repository.ReassignmentSuggestionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The migrations and the main flow on a real PostgreSQL (the Docker setup).
 * Runs only when POSTGRES_TEST_URL is set, e.g. in CI with a postgres service:
 *   POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/ziprun mvn test -Dtest=PostgresIntegrationTest
 * The database must be empty: the test relies on the V2 sample data.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(properties = {
    "spring.datasource.url=${POSTGRES_TEST_URL}",
    "spring.datasource.username=${POSTGRES_TEST_USER:ziprun}",
    "spring.datasource.password=${POSTGRES_TEST_PASSWORD:ziprun}",
    "routing.strategy=rule-based",
    "llm.providers=mock"
})
@AutoConfigureMockMvc
class PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired ReassignmentSuggestionRepository suggestions;

    @Test
    void migrationsSeedTheDatabaseAndTheReplanFlowWorks() throws Exception {
        mvc.perform(get("/agents")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));

        mvc.perform(patch("/agents/AGT-001/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFFLINE\"}"))
            .andExpect(status().isOk());

        long deadline = System.currentTimeMillis() + 15_000;
        long queued = 0;
        while (queued < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            queued = suggestions.findAll().stream().filter(s -> s.getStatus() == SuggestionStatus.PENDING).count();
        }
        assertThat(queued).isEqualTo(3);
        assertThat(orders.findByStatus(OrderStatus.REASSIGNMENT_PENDING)).hasSize(3);

        // the activity log's identity column and the metrics queries work on Postgres too
        mvc.perform(get("/activity")).andExpect(status().isOk()).andExpect(jsonPath("$[0].type").exists());
        mvc.perform(get("/metrics")).andExpect(status().isOk()).andExpect(jsonPath("$.totalSuggestions").value(3));
    }
}
