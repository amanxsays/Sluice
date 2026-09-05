package dev.sluice.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.checkerframework.checker.units.qual.t;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class JobsRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JobsRepository repository;

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
    void enqueueInsertsAPendingJob() {
        Job job = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", "key-123",0);

        assertTrue(job.id() > 0);
        assertEquals("send-email", job.jobType());
        assertEquals("pending", job.status());
        assertNull(job.claimedAt());
    }

    @Test
    void claimAssignsApendingJobToWorker() {
        Job job = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", "key-123",0);

        Optional<Job> claimedByA = repository.claim("worker-A",30);
        Optional<Job> claimedByB = repository.claim("worker-B",30);

        assertTrue(claimedByA.isPresent());
        assertEquals(job.id(), claimedByA.get().id());
        assertEquals(Optional.empty(), claimedByB);
    }

    @Test
    void claimNeverAssignsSameJobToWorker() throws InterruptedException {
        for(int i=1;i<=20;i++) repository.enqueue("send-email-"+i, "{\"to\":\"a@b.com\"}", null,0);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Long> claimedIds = new CopyOnWriteArrayList<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for(int i=1;i<=4;i++){
            String workerId="worker-"+i;
            tasks.add(() -> {
                Optional<Job> claimed;
                while ((claimed = repository.claim(workerId,30)).isPresent()) {
                    claimedIds.add(claimed.get().id());
                }
                return null;
            });
        }

        executor.invokeAll(tasks);
        executor.shutdown();

        assertEquals(20, claimedIds.size());
        assertEquals(20, new HashSet<>(claimedIds).size());
    }

    @Test
    void claimSetsLeaseExpiryApproximatelyLeaseSecondsFromNow(){
        repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);
    
        Instant before = Instant.now();
        Job claimed = repository.claim("worker-A", 30).orElseThrow();
        Instant expectedExpiry = before.plusSeconds(30);

        long diffSeconds =Math.abs(
            Duration.between(expectedExpiry, claimed.leaseExpiresAt()).getSeconds()
        );

        assertTrue(diffSeconds<2);
    }

    @Test
    void heartbeatSucceedsForRightfulOwner(){
        repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);

        Job claimed = repository.claim("worker-A", 30).orElseThrow();

        boolean alive = repository.heartbeat(claimed.id(), "worker-A", 40);

        assertTrue(alive);
    }

    @Test
    void heartbeatFailsForAJobThatNotClaimed(){
        Job job = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);

        boolean alive = repository.heartbeat(job.id(), "worker-A", 40);

        assertFalse(alive);
    }

    @Test
    void reclaimExpiredLeasesMakeDeadTasksReclaimable(){
        repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);
        Job claimed = repository.claim("worker-A", -5).orElseThrow();

        int count = repository.reclaimExpiredLeases(5);

        assertEquals(1, count);

        Job reclaimed = repository.findById(claimed.id()).orElseThrow();
        assertEquals("pending", reclaimed.status());
        assertNull(reclaimed.claimedBy());
    }

    @Test
    void markFailedIncrementsAttemptsAndSetsAvailableAt(){
        repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);
        Job claimed = repository.claim("worker-A", 30).orElseThrow();

        Instant before = Instant.now();
        boolean markedFailed = repository.markFailed(claimed.id(), "worker-A", 10,5);
        Instant backOffTime = before.plusSeconds(10);

        assertTrue(markedFailed);

        Job job = repository.findById(claimed.id()).orElseThrow();

        assertEquals("pending", job.status());
        assertEquals(1, job.attempts());
        long diffSeconds = Math.abs(Duration.between(backOffTime, job.availableAt()).getSeconds());
        assertTrue(diffSeconds < 2);
    }

    @Test
    void markFailedFailsWhenWorkerDoesNotOwnJob(){
        repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);
        Job claimed = repository.claim("worker-A", 30).orElseThrow();

        boolean markedFailed = repository.markFailed(claimed.id(), "worker-B", 13,5);

        assertFalse(markedFailed);
    }

    @Test
    void markFailedDeadLettersJobAfterMaxAttempts(){
        Job job = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);
        int maxAttempts=3;
        for(int i=0;i<maxAttempts;i++){
            Job claimed = repository.claim("worker-A", 30).orElseThrow();
            boolean markedFailed = repository.markFailed(claimed.id(), "worker-A", 0, maxAttempts);
            assertTrue(markedFailed);
        }

        Job foundJob = repository.findById(job.id()).orElseThrow();
        assertEquals("dead_letter", foundJob.status());
        assertEquals(maxAttempts, foundJob.attempts());
    }

    @Test
    void reclaimExpiredLeasesDeadLettersJobAfterMaxAttempts(){
        Job job = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);
        int maxAttempts=3;
        for(int i=0;i<maxAttempts;i++){
            Job claimed = repository.claim("worker-A", -5).orElseThrow();
            int count = repository.reclaimExpiredLeases(maxAttempts);
            assertEquals(1,count);
        }

        Job foundJob = repository.findById(job.id()).orElseThrow();
        assertEquals("dead_letter", foundJob.status());
        assertEquals(maxAttempts, foundJob.attempts());
    }

    @Test 
    void enqueueWithSameIdempotencyKeyReturnsSameJob(){
        Job jobA = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", "key-abc",0);
        Job jobB = repository.enqueue("send-email", "{\"to\":\"m@n.com\"}", "key-abc",0);
        assertEquals(jobA.id(), jobB.id());
        assertEquals(jobA.payload(), jobB.payload());
    }

    @Test 
    void enqueueWithNullIdempotencyKeyAllowsMultipleJobs(){
        Job jobA = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);
        Job jobB = repository.enqueue("send-email", "{\"to\":\"m@n.com\"}", null,0);
        assertNotEquals(jobA.id(), jobB.id());
    }

    @Test 
    void claimPicksHigherPriorityJobFirst(){
        Job normalJob = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", null,0);
        Job urgentJob = repository.enqueue("send-email", "{\"to\":\"m@n.com\"}", null,5);
        Job claimed = repository.claim("worker-A", 30).orElseThrow();
        assertEquals(urgentJob.id(), claimed.id());
    }

}
