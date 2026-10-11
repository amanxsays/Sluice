package dev.sluice.core;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JobsLabConcurrencyTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private PGSimpleDataSource dataSource;
    private JobsRepository repository;

    @BeforeEach void setup() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        try (Connection conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE jobs RESTART IDENTITY");
        }
        repository = new JobsRepository(dataSource);
    }

    @Test void simultaneousClaimsSkipLockedRowsAndStayInsideTheirRun() throws Exception {
        List<Job> jobs = repository.enqueueBatch("sluice-lab:run-a", "{}", null, 0, 20);
        Job otherRun = repository.enqueue("sluice-lab:run-b", "{}", null, 0);
        Job ordinary = repository.enqueue("call-api", "{}", null, 0);
        try (Connection lock = dataSource.getConnection(); var pool = Executors.newFixedThreadPool(16)) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM jobs WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, jobs.getFirst().id());
                try (var rs = statement.executeQuery()) { assertTrue(rs.next()); }
            }
            CountDownLatch ready = new CountDownLatch(16);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Job>> futures = java.util.stream.IntStream.range(0, 16).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository.claimForJobType("worker-" + index, 10, "sluice-lab:run-a").orElseThrow();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            java.util.Set<Long> claimed = new java.util.HashSet<>();
            for (Future<Job> future : futures) {
                Job job = future.get(5, TimeUnit.SECONDS); // Must finish while the first row remains locked.
                assertNotEquals(jobs.getFirst().id(), job.id());
                assertEquals("sluice-lab:run-a", job.jobType());
                assertTrue(claimed.add(job.id()), "Two workers claimed the same job");
            }
            assertEquals("pending", repository.findById(otherRun.id()).orElseThrow().status());
            assertEquals(ordinary.id(), repository.claim("normal-worker", 10).orElseThrow().id());
            assertTrue(repository.claim("normal-worker-2", 10).isEmpty(), "Ordinary workers must leave lab jobs alone");
            lock.rollback();
            assertEquals(jobs.getFirst().id(), repository.claimForJobType("after-unlock", 10, "sluice-lab:run-a").orElseThrow().id());
        }
    }

    @Test void batchResubmissionReturnsOriginalIdsAndBadPayloadRollsBack() {
        List<Job> first = repository.enqueueBatch("call-api", "{}", "batch-key", 0, 10);
        List<Job> repeated = repository.enqueueBatch("call-api", "{}", "batch-key", 0, 10);
        assertEquals(first.stream().map(Job::id).toList(), repeated.stream().map(Job::id).toList());
        assertEquals(10, repository.countByStatus().get("total"));
        assertThrows(JobPersistenceException.class, () -> repository.enqueueBatch("call-api", "invalid JSON", null, 0, 5));
        assertEquals(10, repository.countByStatus().get("total"));
    }
}
