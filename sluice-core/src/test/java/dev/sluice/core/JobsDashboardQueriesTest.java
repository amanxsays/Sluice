package dev.sluice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JobsDashboardQueriesTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JobsRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate();

        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {

            statement.execute(
                    "TRUNCATE TABLE jobs, job_schedules RESTART IDENTITY");
        }

        repository = new JobsRepository(dataSource);
    }

    @Test
    void listSupportsOrderingPaginationAndStatusFiltering() {
        repository.enqueue("demo", "{}", null, 0);
        Job second = repository.enqueue("demo", "{}", null, 0);
        Job third = repository.enqueue("demo", "{}", null, 0);

        // Higher priority ensures this job is claimed first.
        Job fourth = repository.enqueue("demo", "{}", null, 10);

        Job claimed = repository.claim("test-worker", 30)
                .orElseThrow();

        assertEquals(fourth.id(), claimed.id());
        assertTrue(repository.markCompleted(
                claimed.id(), "test-worker"));

        List<Long> pageIds = repository.findRecent(null, 2, 1)
                .stream()
                .map(Job::id)
                .toList();

        assertEquals(
                List.of(third.id(), second.id()),
                pageIds
        );

        List<Job> pendingPage =
                repository.findRecent("pending", 1, 1);

        assertEquals(1, pendingPage.size());
        assertEquals(second.id(), pendingPage.get(0).id());

        List<Job> completed =
                repository.findRecent("completed", 10, 0);

        assertEquals(1, completed.size());
        assertEquals(fourth.id(), completed.get(0).id());

        assertTrue(repository.findRecent(null, 10, 100).isEmpty());
    }

    @Test
    void emptyQueueReturnsZeroCounts() {
        assertEquals(
                Map.of(
                        "total", 0L,
                        "pending", 0L,
                        "claimed", 0L,
                        "completed", 0L,
                        "dead_letter", 0L
                ),
                repository.countByStatus()
        );
    }

    @Test
    void countsIncludeEveryJobStatus() {
        for (int i = 0; i < 4; i++) {
            repository.enqueue("demo", "{}", null, 0);
        }

        Job successful = repository.claim("success-worker", 30)
                .orElseThrow();

        assertTrue(repository.markCompleted(
                successful.id(), "success-worker"));

        Job failed = repository.claim("failure-worker", 30)
                .orElseThrow();

        assertTrue(repository.markFailed(
                failed.id(), "failure-worker", 1, 1));

        repository.claim("active-worker", 30).orElseThrow();

        assertEquals(
                Map.of(
                        "total", 4L,
                        "pending", 1L,
                        "claimed", 1L,
                        "completed", 1L,
                        "dead_letter", 1L
                ),
                repository.countByStatus()
        );
    }
}