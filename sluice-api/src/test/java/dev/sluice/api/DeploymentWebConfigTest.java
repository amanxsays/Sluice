package dev.sluice.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class DeploymentWebConfigTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void close() { context.close(); }

    @Test void allowsJsonPreflightForHostedFrontendOnJobAndTestRoutes() throws Exception {
        for (String path : new String[] {"/jobs", "/jobs/batch", "/tests/runs"}) {
            mvc.perform(options(path).header("Origin", "https://sluice.netlify.app")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "https://sluice.netlify.app"))
                    .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
        }
    }
    @Test void rejectsUnknownBrowserOriginsButAcceptsLocalAndServerRequests() throws Exception {
        mvc.perform(get("/jobs").header("Origin", "https://other.example"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/jobs").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(get("/jobs")).andExpect(status().isOk());
    }
    @Test void embeddedUpstreamReturnsSuccessAndRetryAfterWithoutAnotherService() throws Exception {
        mvc.perform(post("/simulate").param("latencyMs", "0"))
                .andExpect(status().isOk()).andExpect(content().string("Success"));
        mvc.perform(post("/simulate").param("latencyMs", "0").param("shouldFail", "true").param("retryAfterSeconds", "7"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "7"));
        mvc.perform(post("/simulate").param("latencyMs", "-1")).andExpect(status().isBadRequest());
        mvc.perform(post("/simulate").param("latencyMs", "30001")).andExpect(status().isBadRequest());
    }

    @Configuration @EnableWebMvc static class TestConfig {
        @Bean DeploymentWebConfig cors() { return new DeploymentWebConfig(" https://sluice.netlify.app/, http://localhost:5173 "); }
        @Bean SimulationController simulation() { return new SimulationController(); }
        @Bean TestRoutes routes() { return new TestRoutes(); }
    }
    @RestController static class TestRoutes {
        @GetMapping("/jobs") public Map<String, String> jobs() { return Map.of("status", "ok"); }
        @PostMapping({"/jobs", "/jobs/batch", "/tests/runs"}) public Map<String, String> submit() { return Map.of("status", "ok"); }
    }
}
