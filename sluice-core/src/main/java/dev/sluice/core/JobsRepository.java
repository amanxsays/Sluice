package dev.sluice.core;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JobsRepository {
    private final DataSource dataSource;

    public JobsRepository(DataSource dataSource){
        this.dataSource=dataSource;
    }

    public Job enqueue(String jobType,String payloadJson,String idempotencyKey,int priority){
        try (Connection conn = dataSource.getConnection()) {
            return enqueue(conn, jobType, payloadJson, idempotencyKey, priority);
        } catch (SQLException e) {
            throw new JobPersistenceException("Failed to enqueue job of type " + jobType, e);
        }
    }

    private Job enqueue(Connection conn, String jobType, String payloadJson, String idempotencyKey, int priority) throws SQLException {
        String sqlCommand="INSERT INTO jobs (job_type, payload, idempotency_key, priority) VALUES (?, ?::jsonb, ?, ?) ON CONFLICT(idempotency_key) DO NOTHING RETURNING *";
        try (PreparedStatement ps = conn.prepareStatement(sqlCommand)){
                ps.setString(1, jobType);
                ps.setString(2, payloadJson);
                ps.setString(3, idempotencyKey);
                ps.setInt(4, priority);
            try (ResultSet rs=ps.executeQuery()){
                if(!rs.next()){
                    // Reuse this connection: concurrent duplicate submissions must not exhaust the pool.
                    try (PreparedStatement existing = conn.prepareStatement("SELECT * FROM jobs WHERE idempotency_key = ?")) {
                        existing.setString(1, idempotencyKey);
                        try (ResultSet found = existing.executeQuery()) {
                            if (!found.next()) throw new SQLException("Idempotent job missing");
                            return mapRow(found);
                        }
                    }
                }
                return mapRow(rs);
            }
        }
    }

    /** Atomic batch; a supplied key prefix makes resubmitting the same batch idempotent. */
    public List<Job> enqueueBatch(String jobType, String payload, String keyPrefix, int priority, int count) {
        if (count < 1 || count > 100) throw new IllegalArgumentException("Batch size must be 1–100");
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<Job> jobs = new ArrayList<>();
                for (int i = 0; i < count; i++) jobs.add(enqueue(conn, jobType, payload,
                        keyPrefix == null ? null : keyPrefix + ":" + (i + 1), priority));
                conn.commit();
                return jobs;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new JobPersistenceException("Failed to enqueue batch", e);
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
        return claimMatching(workerId, leaseSeconds, null);
    }

    /** Dedicated lab workers claim only their own run; ordinary workers skip lab jobs. */
    public Optional<Job> claimForJobType(String workerId, int leaseSeconds, String jobType) {
        if (jobType == null || !jobType.startsWith("sluice-lab:")) {
            throw new IllegalArgumentException("A reserved lab job type is required");
        }
        return claimMatching(workerId, leaseSeconds, jobType);
    }

    private Optional<Job> claimMatching(String workerId, int leaseSeconds, String jobType) {
        String sqlCommand="""
            WITH next_job AS (
                SELECT id FROM jobs
                WHERE status = 'pending' AND (available_at IS NULL OR available_at <= now())
                    AND %s
                ORDER BY priority DESC, created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE jobs
            SET status = 'claimed', claimed_by = ?, claimed_at = now(), updated_at = now(),  lease_expires_at = now() + make_interval(secs => ?)
            WHERE id IN (SELECT id FROM next_job)
            RETURNING *
            """.formatted(jobType == null
                    ? "job_type NOT LIKE 'sluice-lab:%'"
                    : "job_type = ?");

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)){
            int parameter = 1;
            if (jobType != null) ps.setString(parameter++, jobType);
            ps.setString(parameter++, workerId);
            ps.setInt(parameter, leaseSeconds);

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

    public List<Job> findByJobType(String jobType) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM jobs WHERE job_type = ? ORDER BY id")) {
            ps.setString(1, jobType);
            try (ResultSet rs = ps.executeQuery()) {
                List<Job> jobs = new ArrayList<>();
                while (rs.next()) jobs.add(mapRow(rs));
                return jobs;
            }
        } catch (SQLException e) {
            throw new JobPersistenceException("Failed to list jobs for type " + jobType, e);
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

    public boolean deferRateLimited(long jobId, String workerId, int delaySeconds) {
        if (delaySeconds < 1) {
            throw new IllegalArgumentException("Delay must be at least one second");
        }

        String sqlCommand = """
                UPDATE jobs
                SET status = 'pending',
                    available_at = now() + make_interval(secs => ?),
                    claimed_by = NULL,
                    claimed_at = NULL,
                    lease_expires_at = NULL,
                    updated_at = now()
                WHERE id = ? AND claimed_by = ? AND status = 'claimed'
                """;

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)) {
            ps.setInt(1, delaySeconds);
            ps.setLong(2, jobId);
            ps.setString(3, workerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new JobPersistenceException(
                    "Unable to defer rate-limited job " + jobId, e);
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

    public List<Job> findRecent(String status, int limit, int offset) {
        String sqlCommand = "SELECT * FROM jobs"
                + (status == null ? "" : " WHERE status = ?")
                + " ORDER BY id DESC LIMIT ? OFFSET ?";

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand)) {

            int parameter = 1;

            if (status != null) {
                ps.setString(parameter++, status);
            }

            ps.setInt(parameter++, limit);
            ps.setInt(parameter, offset);

            try (ResultSet rs = ps.executeQuery()) {
                List<Job> jobs = new ArrayList<>();

                while (rs.next()) {
                    jobs.add(mapRow(rs));
                }

                return jobs;
            }

        } catch (SQLException e) {
            throw new JobPersistenceException(
                    "Failed to list jobs", e);
        }
    }

    public Map<String, Long> countByStatus() {
        String sqlCommand = """
                SELECT
                    COUNT(*) AS total,
                    COUNT(*) FILTER (
                        WHERE status = 'pending'
                    ) AS pending,
                    COUNT(*) FILTER (
                        WHERE status = 'claimed'
                    ) AS claimed,
                    COUNT(*) FILTER (
                        WHERE status = 'completed'
                    ) AS completed,
                    COUNT(*) FILTER (
                        WHERE status = 'dead_letter'
                    ) AS dead_letter
                FROM jobs
                """;

        try (Connection conn = dataSource.getConnection();
            PreparedStatement ps = conn.prepareStatement(sqlCommand);
            ResultSet rs = ps.executeQuery()) {

            if (!rs.next()) {
                throw new JobPersistenceException(
                        "Job statistics query returned no row",
                        new SQLException("Expected one aggregate row"));
            }

            return Map.of(
                    "total", rs.getLong("total"),
                    "pending", rs.getLong("pending"),
                    "claimed", rs.getLong("claimed"),
                    "completed", rs.getLong("completed"),
                    "dead_letter", rs.getLong("dead_letter")
            );

        } catch (SQLException e) {
            throw new JobPersistenceException(
                    "Failed to count jobs by status", e);
        }
    }

}
