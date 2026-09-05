package dev.sluice.core;

import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

public class JobSchedulesRepository {
    private final DataSource dataSource;

    public JobSchedulesRepository(DataSource dataSource){
        this.dataSource=dataSource;
    }

    private JobSchedule mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String cronExpression = rs.getString("cron_expression");
        String jobType = rs.getString("job_type");
        String payload = rs.getString("payload");
        int priority = rs.getInt("priority");
        Instant nextRunAt = toInstant(rs.getTimestamp("next_run_at"));
        boolean enabled = rs.getBoolean("enabled");
        Instant createdAt = toInstant(rs.getTimestamp("created_at"));
        Instant updatedAt = toInstant(rs.getTimestamp("updated_at"));

        return new JobSchedule(id, cronExpression, jobType, payload, priority, nextRunAt, enabled, createdAt, updatedAt);
    }

    private Instant toInstant(Timestamp ts) {
        if (ts == null) return null;
        return ts.toInstant();
    }

    public List<JobSchedule> findDueSchedules(){
        String sqlCommand = """
                SELECT * FROM job_schedules 
                WHERE enabled = true AND next_run_at <= now()
                """;
        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
            try (ResultSet rs = ps.executeQuery()){
                List<JobSchedule> jobScheduleList = new ArrayList<>();
                while(rs.next()){
                    jobScheduleList.add(mapRow(rs));
                }
                return jobScheduleList;
            }
        } catch (SQLException e){
            throw new JobPersistenceException( "Can Not Find Job Schedules Which Are Due", e);
        }
    }

    public void fireSchedule(JobSchedule schedule, JobsRepository jobsRepository, Instant nextRunAt){
        jobsRepository.enqueue(schedule.jobType(), schedule.payload(), null, schedule.priority());

        String sqlCommand = """
                UPDATE job_schedules
                SET next_run_at = ?,
                    updated_at = now()
                WHERE id = ?
                """;

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)) {

            ps.setTimestamp(1, Timestamp.from(nextRunAt));
            ps.setLong(2, schedule.id());
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new JobPersistenceException("Failed to advance schedule " + schedule.id(), e);
        }

    }

}
