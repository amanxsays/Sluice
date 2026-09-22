package dev.sluice.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
        Worker worker = new Worker(repository, Map.of(), "worker-A", 30, new BackoffCalculator(1, 60), 5,new SimpleMeterRegistry());

        boolean result = worker.processOnce();

        assertFalse(result);
    }

    @Test 
    void processOnceReturnsTrueOnFullChainWorked(){
        Job job = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null, 0);
        Map<String, JobHandler> handlers = Map.of("send-email", new ConsoleLoggingHandler());
        Worker worker = new Worker(repository, handlers, "worker-A", 30, new BackoffCalculator(1, 60), 5,new SimpleMeterRegistry());
        boolean result = worker.processOnce();
        assertTrue(result);

        Job jobFound = repository.findById(job.id()).orElseThrow();
        assertEquals("completed", jobFound.status());
    }

    @Test 
    void processOnceStillReturnsTrueButFailsJobOnMissingHandler(){
        Job job = repository.enqueue("set-reminder", "{\"to\":\"a@b.com\"}", null, 0);
        Worker worker = new Worker(repository, Map.of(), "worker-A", 30, new BackoffCalculator(1, 60), 5,new SimpleMeterRegistry());
        boolean result = worker.processOnce();
        assertTrue(result);

        Job jobFound = repository.findById(job.id()).orElseThrow();
        assertEquals("pending", jobFound.status());
        assertEquals(1, jobFound.attempts());
    }

    @Test
    void processOnceUsesRetryAfterOnRateLimit() throws Exception {
        assertUpstreamRetryAfter("7", 7);
    }

    @Test
    void missingRetryAfterStillDefaultsToOneSecond() throws Exception {
        assertUpstreamRetryAfter(null, 1);
    }

    private void assertUpstreamRetryAfter(
            String header, int expectedDelay) throws Exception {
        AtomicInteger requests = new AtomicInteger();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/simulate", exchange -> {
            requests.incrementAndGet();
            if (header != null) {
                exchange.getResponseHeaders().set("Retry-After", header);
            }
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });

        server.start();
        try {
            TokenBucket bucket = new TokenBucket(1, 1, () -> 0L);
            JobHandler upstream = new SimulatedApiCallHandler(
                    "http://127.0.0.1:" + server.getAddress().getPort()
                            + "/simulate");

            JobHandler limited = job -> {
                bucket.acquireOrThrow();
                upstream.handle(job);
            };

            Job job = repository.enqueue(
                    "call-api",
                    "{\"latencyMs\":0,\"shouldFail\":true,\"retryAfterSeconds\":7}",
                    null,
                    0);

            Worker worker = new Worker(
                    repository,
                    Map.of("call-api", limited),
                    "worker-A",
                    30,
                    new BackoffCalculator(1, 60),
                    5,
                    new SimpleMeterRegistry());

            assertTrue(worker.processOnce());

            Job saved = repository.findById(job.id()).orElseThrow();

            assertEquals(1, requests.get());
            assertEquals("pending", saved.status());
            assertEquals(1, saved.attempts());
            assertEquals(
                    saved.updatedAt().plusSeconds(expectedDelay),
                    saved.availableAt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void concurrentWorkersShareBucketAndDeferWithoutSpendingAttempts()
            throws Exception {
        int workerCount = 8;
        int capacity = 3;

        // Keep deferred jobs unavailable for the entire concurrency test.
        TokenBucket bucket = new TokenBucket(capacity, 0.01, () -> 0L);

        AtomicInteger upstreamCalls = new AtomicInteger();
        JobHandler limited = job -> {
            bucket.acquireOrThrow();
            upstreamCalls.incrementAndGet();
        };
        Map<String, JobHandler> handlers = Map.of("call-api", limited);

        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            jobs.add(repository.enqueue("call-api", "{}", null, 0));
        }

        var executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> results = new ArrayList<>();

            for (int i = 0; i < workerCount; i++) {
                Worker worker = new Worker(
                        repository,
                        handlers,
                        "worker-" + i,
                        30,
                        new BackoffCalculator(1, 60),
                        1,
                        new SimpleMeterRegistry());

                results.add(executor.submit(() -> {
                    start.await();
                    return worker.processOnce();
                }));
            }

            start.countDown();

            for (Future<Boolean> result : results) {
                assertTrue(result.get(10, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(capacity, upstreamCalls.get());

        int completed = 0;
        for (Job job : jobs) {
            Job saved = repository.findById(job.id()).orElseThrow();

            assertEquals(0, saved.attempts());

            if (saved.status().equals("completed")) {
                completed++;
            } else {
                assertEquals("pending", saved.status());
                assertEquals(
                        saved.updatedAt().plusSeconds(100),
                        saved.availableAt());
                assertNull(saved.claimedBy());
                assertNull(saved.claimedAt());
                assertNull(saved.leaseExpiresAt());
            }
        }

        assertEquals(capacity, completed);
    }

    @Test
    void localDeferralRequiresCurrentClaimOwner() {
        Job job = repository.enqueue("call-api", "{}", null, 0);
        repository.claim("worker-A", 30).orElseThrow();

        assertFalse(repository.deferRateLimited(job.id(), "worker-B", 1));
        assertTrue(repository.deferRateLimited(job.id(), "worker-A", 1));
        assertFalse(repository.deferRateLimited(job.id(), "worker-A", 1));
    }
}
