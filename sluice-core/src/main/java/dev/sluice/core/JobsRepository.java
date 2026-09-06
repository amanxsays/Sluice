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

    public Job enqueue(String jobType,String payloadJson,String idempotencyKey,int priority){
        String sqlCommand="INSERT INTO jobs (job_type, payload, idempotency_key, priority) VALUES (?, ?::jsonb, ?, ?) ON CONFLICT(idempotency_key) DO NOTHING RETURNING *";
        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
                ps.setString(1, jobType);
                ps.setString(2, payloadJson);
                ps.setString(3, idempotencyKey);
                ps.setInt(4, priority);
            try (ResultSet rs=ps.executeQuery()){
                if(!rs.next()){
                    return findByIdempotencyKey(idempotencyKey).orElseThrow();
                }
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
        Instant leaseExpiresAt=toInstant(rs.getTimestamp("lease_expires_at"));
        int attempts=rs.getInt("attempts");
        Instant availableAt=toInstant(rs.getTimestamp("available_at"));
        int priority=rs.getInt("priority");

        return new Job(id,jobType,payLoad,idempotencyKey,status,claimedBy,claimedAt,createdAt,updatedAt,leaseExpiresAt,attempts,availableAt,priority);
    }
    private Instant toInstant(Timestamp ts){
        if(ts==null) return null;
        return ts.toInstant();
    }

    public Optional<Job> claim(String workerId,int leaseSeconds){
        String sqlCommand="""
            WITH next_job AS (
                SELECT id FROM jobs
                WHERE status = 'pending' AND (available_at IS NULL OR available_at <= now())
                ORDER BY priority DESC, created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE jobs
            SET status = 'claimed', claimed_by = ?, claimed_at = now(), updated_at = now(),  lease_expires_at = now() + make_interval(secs => ?)
            WHERE id IN (SELECT id FROM next_job)
            RETURNING *
            """;

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
            ps.setString(1, workerId);
            ps.setInt(2, leaseSeconds);

            try(ResultSet rs = ps.executeQuery()){
                if(!rs.next()){
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e ){
            throw new JobPersistenceException("Faild to claim job for worker "+workerId, e);
        }
    }

    public boolean heartbeat(long jobId, String workerId, int extendLeaseSecondsBy){
        String sqlCommand = """
                UPDATE jobs
                SET lease_expires_at = now() + make_interval(secs => ?),
                    updated_at = now()
                WHERE id = ? AND claimed_by = ? AND status = 'claimed'
                """;

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps=conn.prepareStatement(sqlCommand);){
            
            ps.setInt(1,extendLeaseSecondsBy);
            ps.setLong(2, jobId);
            ps.setString(3, workerId);

            int rowsUpdated = ps.executeUpdate();
            return rowsUpdated>0;
        } catch (SQLException e){
            throw new JobPersistenceException("Failed to send heartbeat for job " + jobId, e);
        }
    }

    public int reclaimExpiredLeases(int maxAttempts){
        String sqlCommand = """
                UPDATE jobs
                SET status = CASE WHEN attempts + 1 >= ? THEN 'dead_letter' ELSE 'pending' END,
                    claimed_by = NULL,
                    lease_expires_at = NULL,
                    updated_at = now(),
                    attempts = attempts + 1
                WHERE status = 'claimed' AND lease_expires_at < now()
                """;
        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
            ps.setInt(1, maxAttempts);
            return ps.executeUpdate();
        } catch (SQLException e){
            throw new JobPersistenceException("Failed to reclaim expired leases", e);
        }
    }

    public Optional<Job> findById(long jobId){
        String sqlCommand = "SELECT * FROM jobs WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
            
                ps.setLong(1, jobId);

                try (ResultSet rs = ps.executeQuery()){
                    if (!rs.next()){
                        return Optional.empty();
                    }
                    return Optional.of(mapRow(rs));
                }
        } catch (SQLException e){
            throw new JobPersistenceException("Failed to find job " + jobId, e);
        }
    }

    public boolean markFailed(long jobId, String workerId, int backoffSeconds, int maxAttempts){
        String sqlCommand = """
                UPDATE jobs
                SET attempts = attempts + 1,
                    updated_at = now(),
                    status = CASE WHEN attempts + 1 >= ? THEN 'dead_letter' ELSE 'pending' END,
                    available_at = CASE WHEN attempts + 1 >= ? THEN NULL ELSE now() + make_interval(secs => ?) END
                WHERE id = ? AND claimed_by = ?
                """;
        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
            
            ps.setInt(1, maxAttempts);
            ps.setInt(2, maxAttempts);
            ps.setInt(3,backoffSeconds);
            ps.setLong(4, jobId);
            ps.setString(5, workerId);

            return ps.executeUpdate() > 0;
        } catch ( SQLException e){
            throw new JobPersistenceException("Unable to make Job attempt as failed with jobId "+jobId, e);
        }
    }
    
    public Optional<Job> findByIdempotencyKey(String idempotencyKey){
        String sqlCommand = "SELECT * FROM jobs WHERE idempotency_key = ?";

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
            
                ps.setString(1, idempotencyKey);

                try (ResultSet rs = ps.executeQuery()){
                    if (!rs.next()){
                        return Optional.empty();
                    }
                    return Optional.of(mapRow(rs));
                }
        } catch (SQLException e){
            throw new JobPersistenceException("Failed to find job with idempotency_key " + idempotencyKey, e);
        }
    }

    public boolean markCompleted(long jobId, String workerId){
        String sqlCommand = """
                UPDATE jobs
                SET status = 'completed',
                    updated_at = now()
                WHERE id = ? AND claimed_by = ? AND status = 'claimed'
                """;

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps=conn.prepareStatement(sqlCommand);){
            
            ps.setLong(1, jobId);
            ps.setString(2, workerId);

            int rowsUpdated = ps.executeUpdate();
            return rowsUpdated>0;
        } catch (SQLException e){
            throw new JobPersistenceException("Failed to mark job completed with job " + jobId, e);
        }
    }

}