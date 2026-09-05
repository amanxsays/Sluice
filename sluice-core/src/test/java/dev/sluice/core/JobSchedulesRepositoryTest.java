package dev.sluice.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers 
public class JobSchedulesRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JobsRepository repository;
    private JobSchedulesRepository schedulesRepository;
    private CronScheduleCalculator cronCalculator = new CronScheduleCalculator();

    private PGSimpleDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException{

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        Flyway.configure().dataSource(dataSource).load().migrate();

        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE jobs, job_schedules RESTART IDENTITY");
        }

        repository = new JobsRepository(dataSource);
        schedulesRepository = new JobSchedulesRepository(dataSource);
    }

    @Test
    void fireScheduleSpawnsJobAndAdvancesNextRunAt() throws SQLException {
        String sqlCommand = """
                INSERT INTO job_schedules (cron_expression, job_type, payload, priority, next_run_at)
                VALUES (?, ?, ?::jsonb, ?, now() - make_interval(mins => 5))
                RETURNING *
                """;

        JobSchedule schedule;
        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)) {

            ps.setString(1, "* * * * *");
            ps.setString(2, "send-report");
            ps.setString(3, "{}");
            ps.setInt(4, 0);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                schedule = new JobSchedule(
                        rs.getLong("id"),
                        rs.getString("cron_expression"),
                        rs.getString("job_type"),
                        rs.getString("payload"),
                        rs.getInt("priority"),
                        rs.getTimestamp("next_run_at").toInstant(),
                        rs.getBoolean("enabled"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                );
            }
        }
           Instant nextRunAt = cronCalculator.nextRunAfter(schedule.cronExpression(), Instant.now());
        schedulesRepository.fireSchedule(schedule, repository, nextRunAt);

        Job spawnedJob = repository.findById(1L).orElseThrow();
        assertEquals("send-report", spawnedJob.jobType());

        List<JobSchedule> stillDue = schedulesRepository.findDueSchedules();
        assertTrue(stillDue.isEmpty());
    }
}
