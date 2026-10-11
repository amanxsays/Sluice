package dev.sluice.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.sluice.core.JobsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TestLabServiceTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private HikariDataSource dataSource;
    private JobsRepository repository;
    private TestLabService lab;
    private SimpleMeterRegistry metrics;
    private HttpServer upstream;
    private ExecutorService httpThreads;
    private ScheduledExecutorService reaper;

    @BeforeEach void setup() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(3); // Forty duplicate submissions must work even with a small pool.
        config.setConnectionTimeout(3000);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).load().migrate();
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE jobs RESTART IDENTITY");
        }
        repository = new JobsRepository(dataSource);
        metrics = new SimpleMeterRegistry();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpThreads = Executors.newCachedThreadPool();
        upstream.setExecutor(httpThreads);
        upstream.createContext("/simulate", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            int latency = Integer.parseInt(query.split("latencyMs=")[1].split("&")[0]);
            int delay = Integer.parseInt(query.split("retryAfterSeconds=")[1].split("&")[0]);
            try { Thread.sleep(latency); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            boolean fail = query.contains("shouldFail=true");
            if (fail) exchange.getResponseHeaders().add("Retry-After", String.valueOf(delay));
            exchange.sendResponseHeaders(fail ? 429 : 200, -1);
            exchange.close();
        });
        upstream.start();
        lab = new TestLabService(repository, dataSource, metrics, "http://127.0.0.1:" + upstream.getAddress().getPort() + "/simulate");
        reaper = Executors.newSingleThreadScheduledExecutor();
        LeaseReaper scheduledReaper = new LeaseReaper(repository, 5);
        reaper.scheduleWithFixedDelay(scheduledReaper::recoverExpiredJobs, 0, 200, TimeUnit.MILLISECONDS);
    }

    @AfterEach void close() {
        lab.close();
        reaper.shutdownNow();
        upstream.stop(0);
        httpThreads.shutdownNow();
        dataSource.close();
        metrics.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"batch", "idempotency", "locking", "heartbeat", "lease-expiry", "retry", "rate-limit"})
    void scenariosVerifyRealQueueBehavior(String scenario) throws Exception {
        boolean single = java.util.Set.of("heartbeat", "lease-expiry", "retry").contains(scenario);
        int count = scenario.equals("idempotency") ? 40 : single ? 1 : 6;
        int workers = single ? 1 : 4;
        var options = new TestLabService.Options(scenario, count, workers,
                scenario.equals("heartbeat") ? 3000 : 1000, 250, 1, 2, 1);
        var ordinary = repository.enqueue("call-api", "{}", null, 0);
        var started = lab.start(options);
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            TestLabService.Snapshot result;
            do {
                Thread.sleep(100);
                result = lab.get(started.id());
            } while (result.status().equals("running"));
            assertEquals("passed", result.status(), result.error() + " " + result.checks());
            assertFalse(result.checks().isEmpty());
            assertTrue(result.checks().stream().allMatch(TestLabService.Check::passed));
            assertTrue(result.peakClaimed() <= workers);
            assertEquals(single || scenario.equals("idempotency") ? 1 : count, result.jobs().size());
            assertEquals("pending", repository.findById(ordinary.id()).orElseThrow().status(), "Lab workers claimed an unrelated job");
            if (scenario.equals("idempotency")) assertEquals(40, result.returnedIds().size());
            if (scenario.equals("locking")) assertTrue(result.events().stream().anyMatch(event -> event.kind().equals("locked")));
            if (scenario.equals("lease-expiry")) assertEquals(1, result.jobs().getFirst().attempts());
            if (scenario.equals("heartbeat")) assertTrue(result.heartbeatCount() >= 2);
        });
    }

    @Test void validatesSettingsAndRejectsOverlappingRuns() {
        assertThrows(ResponseStatusException.class, () -> lab.start(new TestLabService.Options("batch", 2, 4, 1000, 250, 2, 3, 1)));
        assertThrows(ResponseStatusException.class, () -> lab.start(new TestLabService.Options("heartbeat", 1, 1, 1000, 250, 2, 3, 1)));
        lab.start(new TestLabService.Options("batch", 5, 2, 1000, 250, 2, 3, 1));
        var conflict = assertThrows(ResponseStatusException.class, () -> lab.start(new TestLabService.Options("batch", 5, 2, 1000, 250, 2, 3, 1)));
        assertEquals(409, conflict.getStatusCode().value());
    }
}
