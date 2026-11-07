package com.pratham.workerservice.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Every state transition is a single conditional UPDATE, so correctness never depends on
 * Redis delivering an ID exactly once:
 *
 *  - claim():      QUEUED -> PROCESSING only if still QUEUED (duplicate IDs are harmless)
 *  - markX():      fenced on (worker_id, PROCESSING), so a worker that lost its job to the
 *                  reaper can't overwrite the new owner's result
 *  - sweeps:       FOR UPDATE SKIP LOCKED, so every container can run the schedulers
 *                  concurrently without blocking each other or double-claiming rows
 */
@Repository
public class JobStore {

    public record ClaimedJob(long id, String type, String payload, int priority,
                             int retryCount, int maxRetries, int attempts, OffsetDateTime createdAt) {
    }

    public record Requeue(long id, int priority) {
    }

    public record JobState(long id, String status, int priority) {
    }

    private static final RowMapper<Requeue> REQUEUE = (rs, i) -> new Requeue(rs.getLong("id"), rs.getInt("priority"));

    private final JdbcTemplate jdbc;

    public JobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ClaimedJob> claim(long id, String workerId) {
        List<ClaimedJob> rows = jdbc.query("""
                UPDATE jobs
                   SET status = 'PROCESSING', worker_id = ?, attempts = attempts + 1,
                       started_at = now(), heartbeat_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'QUEUED'
             RETURNING id, type, payload, priority, retry_count, max_retries, attempts, created_at
                """, (rs, i) -> new ClaimedJob(rs.getLong("id"), rs.getString("type"), rs.getString("payload"),
                rs.getInt("priority"), rs.getInt("retry_count"), rs.getInt("max_retries"), rs.getInt("attempts"),
                rs.getObject("created_at", OffsetDateTime.class)), workerId, id);
        return rows.stream().findFirst();
    }

    /** @return end-to-end latency in ms (created -> completed), or empty if this worker no longer owns the job */
    public Optional<Long> markSuccess(long id, String workerId) {
        List<Long> rows = jdbc.query("""
                UPDATE jobs
                   SET status = 'SUCCESS', completed_at = now(), updated_at = now(),
                       heartbeat_at = NULL, last_error = NULL
                 WHERE id = ? AND worker_id = ? AND status = 'PROCESSING'
             RETURNING (extract(epoch FROM completed_at - created_at) * 1000)::bigint AS latency_ms
                """, (rs, i) -> rs.getLong("latency_ms"), id, workerId);
        return rows.stream().findFirst();
    }

    public boolean markRetry(long id, String workerId, long delayMs, String error) {
        return jdbc.update("""
                UPDATE jobs
                   SET status = 'RETRYING', retry_count = retry_count + 1,
                       next_retry_at = now() + (? * interval '1 millisecond'),
                       last_error = ?, worker_id = NULL, heartbeat_at = NULL, updated_at = now()
                 WHERE id = ? AND worker_id = ? AND status = 'PROCESSING'
                """, delayMs, truncate(error), id, workerId) == 1;
    }

    public boolean markDead(long id, String workerId, String error) {
        return jdbc.update("""
                UPDATE jobs
                   SET status = 'DLQ', completed_at = now(), last_error = ?,
                       heartbeat_at = NULL, updated_at = now()
                 WHERE id = ? AND worker_id = ? AND status = 'PROCESSING'
                """, truncate(error), id, workerId) == 1;
    }

    public int heartbeat(Collection<Long> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "UPDATE jobs SET heartbeat_at = now() WHERE id = ANY(?) AND status = 'PROCESSING'");
            Array array = con.createArrayOf("bigint", ids.toArray());
            ps.setArray(1, array);
            return ps;
        });
    }

    /** RETRYING jobs whose backoff has elapsed -> QUEUED. Safe to run from many containers. */
    public List<Requeue> releaseDueRetries(int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM jobs
                     WHERE status = 'RETRYING' AND next_retry_at <= now()
                     ORDER BY next_retry_at
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED)
                UPDATE jobs j
                   SET status = 'QUEUED', next_retry_at = NULL, updated_at = now()
                  FROM due WHERE j.id = due.id
             RETURNING j.id, j.priority
                """, REQUEUE, limit);
    }

    /** PROCESSING jobs whose worker stopped heart-beating -> QUEUED. */
    public List<Requeue> reclaimStale(int staleAfterSeconds, int limit) {
        return jdbc.query("""
                WITH stale AS (
                    SELECT id FROM jobs
                     WHERE status = 'PROCESSING' AND heartbeat_at < now() - (? * interval '1 second')
                     ORDER BY heartbeat_at
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED)
                UPDATE jobs j
                   SET status = 'QUEUED', worker_id = NULL, heartbeat_at = NULL,
                       recovered_count = recovered_count + 1,
                       last_error = 'worker heartbeat lost; job reclaimed', updated_at = now()
                  FROM stale WHERE j.id = stale.id
             RETURNING j.id, j.priority
                """, REQUEUE, staleAfterSeconds, limit);
    }

    /**
     * QUEUED rows not touched for a while. Only called when Redis queues are empty, which means
     * these IDs never made it into Redis (e.g. API crashed between commit and LPUSH, or Redis
     * lost data). Touches updated_at so the same row isn't re-pushed on the next pass.
     */
    public List<Requeue> sweepOrphans(int olderThanSeconds, int limit) {
        return jdbc.query("""
                WITH orphan AS (
                    SELECT id FROM jobs
                     WHERE status = 'QUEUED' AND updated_at < now() - (? * interval '1 second')
                     ORDER BY priority, created_at
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED)
                UPDATE jobs j
                   SET updated_at = now(), recovered_count = recovered_count + 1
                  FROM orphan WHERE j.id = orphan.id
             RETURNING j.id, j.priority
                """, REQUEUE, olderThanSeconds, limit);
    }

    public Map<Long, JobState> states(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, JobState> result = new HashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, status, priority FROM jobs WHERE id = ANY(?)");
            ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
            return ps;
        }, rs -> {
            result.put(rs.getLong("id"), new JobState(rs.getLong("id"), rs.getString("status"), rs.getInt("priority")));
        });
        return result;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
