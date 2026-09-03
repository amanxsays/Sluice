package dev.sluice.core;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

public class JobsRepository {
    private final DataSource dataSource;

    public JobsRepository(DataSource dataSource){
        this.dataSource=dataSource;
    }

    public Job enqueue(String jobType,String payloadJson,String idempotencyKey){
        String sqlCommand="INSERT INTO jobs (job_type, payload, idempotency_key) VALUES (?, ?::jsonb, ?) RETURNING *";
        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
                ps.setString(1, jobType);
                ps.setString(2, payloadJson);
                ps.setString(3, idempotencyKey);
            try (ResultSet rs=ps.executeQuery()){
                rs.next();
                return mapRow(rs);
            }
        }
        catch (SQLException e){
            throw new JobPersistenceException("Failed to enqueue job of type "+jobType, e);
        }
    }
    private Job mapRow(ResultSet rs) throws SQLException{
        long id=rs.getLong("id");
        String jobType=rs.getString("job_type");
        String payLoad=rs.getString("payLoad");
        String idempotencyKey=rs.getString("idempotency_key");
        String status=rs.getString("status");
        String claimedBy=rs.getString("claimed_by");
        Instant claimedAt=toInstant(rs.getTimestamp("claimed_at"));
        Instant createdAt=toInstant(rs.getTimestamp("created_at"));
        Instant updatedAt=toInstant(rs.getTimestamp("updated_at"));

        return new Job(id,jobType,payLoad,idempotencyKey,status,claimedBy,claimedAt,createdAt,updatedAt);
    }
    private Instant toInstant(Timestamp ts){
        if(ts==null) return null;
        return ts.toInstant();
    }

    public Optional<Job> claim(String workerId){
        String sqlCommand="""
            WITH next_job AS (
                SELECT id FROM jobs
                WHERE status = 'pending'
                ORDER BY created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE jobs
            SET status = 'claimed', claimed_by = ?, claimed_at = now(), updated_at = now()
            WHERE id IN (SELECT id FROM next_job)
            RETURNING *
            """;

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
            ps.setString(1, workerId);

            try(ResultSet rs = ps.executeQuery()){
                if(!rs.next()){
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e ){
            throw new JobPersistenceException("Faild to claim job for worker ", e);
        }
    }
}