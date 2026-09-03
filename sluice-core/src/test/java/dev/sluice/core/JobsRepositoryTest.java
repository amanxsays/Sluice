package dev.sluice.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        Job job = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", "key-123");

        assertTrue(job.id() > 0);
        assertEquals("send-email", job.jobType());
        assertEquals("pending", job.status());
        assertNull(job.claimedAt());
    }

    @Test
    void claimAssignsApendingJobToWorker() {
        Job job = repository.enqueue("send-email", "{\"to\":\"a@b.com\"}", "key-123");

        Optional<Job> claimedByA = repository.claim("worker-A");
        Optional<Job> claimedByB = repository.claim("worker-B");

        assertTrue(claimedByA.isPresent());
        assertEquals(job.id(), claimedByA.get().id());
        assertEquals(Optional.empty(), claimedByB);
    }

    @Test
    void claimNeverAssignsSameJobToWorker() throws InterruptedException {
        for(int i=1;i<=20;i++) repository.enqueue("send-email-"+i, "{\"to\":\"a@b.com\"}", null);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Long> claimedIds = new CopyOnWriteArrayList<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for(int i=1;i<=4;i++){
            String workerId="worker-"+i;
            tasks.add(() -> {
                Optional<Job> claimed;
                while ((claimed = repository.claim(workerId)).isPresent()) {
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
}
