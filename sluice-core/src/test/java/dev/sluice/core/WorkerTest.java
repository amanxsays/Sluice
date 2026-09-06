package dev.sluice.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Handler;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers 
public class WorkerTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JobsRepository repository;
    private Worker worker;

    @BeforeEach
    void setUp() throws SQLException{

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure().dataSource(dataSource).load().migrate();

        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE jobs RESTART IDENTITY");
        }

        repository = new JobsRepository(dataSource);
    }

    @Test
    void processOnceReturnsFalseOnEmptyQueue(){
        Worker worker = new Worker(repository, Map.of(), "worker-A", 30, new BackoffCalculator(1, 60), 5);

        boolean result = worker.processOnce();

        assertFalse(result);
    }

    @Test 
    void processOnceReturnsTrueOnFullChainWorked(){
        Job job = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null, 0);
        Map<String, JobHandler> handlers = Map.of("send-email", new ConsoleLoggingHandler());
        Worker worker = new Worker(repository, handlers, "worker-A", 30, new BackoffCalculator(1, 60), 5);
        boolean result = worker.processOnce();
        assertTrue(result);

        Job jobFound = repository.findById(job.id()).orElseThrow();
        assertEquals("completed", jobFound.status());
    }

    @Test 
    void processOnceStillReturnsTrueButFailsJobOnMissingHandler(){
        Job job = repository.enqueue("set-reminder", "{\"to\":\"a@b.com\"}", null, 0);
        Worker worker = new Worker(repository, Map.of(), "worker-A", 30, new BackoffCalculator(1, 60), 5);
        boolean result = worker.processOnce();
        assertTrue(result);

        Job jobFound = repository.findById(job.id()).orElseThrow();
        assertEquals("pending", jobFound.status());
        assertEquals(1, jobFound.attempts());
    }

    @Test
    void processOnceUsesRetryAfterOnRateLimit(){
        Job job = repository.enqueue("call-api", "{\"latencyMs\":0,\"shouldFail\":true,\"retryAfterSeconds\":7}", null, 0);
        Map<String,JobHandler> handlers = Map.of("call-api", new SimulatedApiCallHandler("http://localhost:8081/simulate"));

        Worker worker = new Worker(repository, handlers, "worker-A", 30, new BackoffCalculator(1, 60), 5);
        
        Instant before = Instant.now();
        boolean result = worker.processOnce();
        Instant expectedAvailableAt = before.plusSeconds(7);
        assertTrue(result);

        Job jobFound = repository.findById(job.id()).orElseThrow();
        assertEquals("pending", jobFound.status());
        long diffSeconds = Math.abs(Duration.between(expectedAvailableAt, jobFound.availableAt()).getSeconds());
        assertTrue(diffSeconds < 2);
        
    }
}
