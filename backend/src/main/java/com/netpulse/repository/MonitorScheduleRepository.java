package com.netpulse.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.netpulse.schedule.DueMonitorJob;

/**
 * Durable schedule leases for enabled monitors.
 * Due claims and publish leases use {@code FOR UPDATE SKIP LOCKED} so multiple
 * backend instances cannot enqueue the same monitor interval twice.
 */
@Repository
public class MonitorScheduleRepository {

    /**
     * Visible for regression tests: claim SQL must cast the bound instant to
     * {@code timestamptz} before adding an interval (JDBC {@code ?} is otherwise
     * inferred as {@code interval} and PostgreSQL rejects the assignment).
     */
    public static final String CLAIM_DUE_SQL = """
            WITH due AS (
                SELECT s.monitor_id
                FROM monitor_schedules s
                INNER JOIN monitors m ON m.id = s.monitor_id
                WHERE m.enabled = TRUE
                  AND s.next_run_at <= CAST(? AS TIMESTAMPTZ)
                  AND s.pending_job_id IS NULL
                ORDER BY s.next_run_at ASC
                FOR UPDATE OF s SKIP LOCKED
                LIMIT ?
            )
            UPDATE monitor_schedules s
            SET pending_job_id = gen_random_uuid(),
                pending_scheduled_at = CAST(? AS TIMESTAMPTZ),
                next_run_at = CAST(? AS TIMESTAMPTZ)
                    + (m.interval_seconds * INTERVAL '1 second'),
                publish_lease_until = NULL,
                updated_at = CAST(? AS TIMESTAMPTZ)
            FROM due
            INNER JOIN monitors m ON m.id = due.monitor_id
            WHERE s.monitor_id = due.monitor_id
            RETURNING s.monitor_id, s.pending_job_id, s.pending_scheduled_at,
                      m.url, m.interval_seconds
            """;

    /**
     * Visible for regression tests: expired or unset publish leases on rows that
     * already have {@code pending_job_id} must be reacquireable. Must not filter on
     * {@code next_run_at} (that would strand pending retries until the next interval).
     */
    public static final String LEASE_PENDING_SQL = """
            WITH due AS (
                SELECT s.monitor_id
                FROM monitor_schedules s
                INNER JOIN monitors m ON m.id = s.monitor_id
                WHERE s.pending_job_id IS NOT NULL
                  AND m.enabled = TRUE
                  AND (s.publish_lease_until IS NULL
                       OR s.publish_lease_until <= CAST(? AS TIMESTAMPTZ))
                ORDER BY s.pending_scheduled_at ASC NULLS FIRST
                FOR UPDATE OF s SKIP LOCKED
                LIMIT ?
            )
            UPDATE monitor_schedules s
            SET publish_lease_until = CAST(? AS TIMESTAMPTZ),
                updated_at = CAST(? AS TIMESTAMPTZ)
            FROM due
            INNER JOIN monitors m ON m.id = due.monitor_id
            WHERE s.monitor_id = due.monitor_id
            RETURNING s.monitor_id, s.pending_job_id, s.pending_scheduled_at,
                      m.url, m.interval_seconds
            """;

    private static final RowMapper<DueMonitorJob> DUE_MAPPER = (rs, rowNum) -> {
        Timestamp scheduledTs = rs.getTimestamp("pending_scheduled_at");
        Instant scheduledAt = scheduledTs != null ? scheduledTs.toInstant() : Instant.EPOCH;
        return new DueMonitorJob(
                (UUID) rs.getObject("monitor_id"),
                (UUID) rs.getObject("pending_job_id"),
                rs.getString("url"),
                scheduledAt,
                rs.getInt("interval_seconds"));
    };

    private final JdbcTemplate jdbcTemplate;

    public MonitorScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates schedule rows for enabled monitors that do not yet have one.
     * New monitors become due immediately ({@code next_run_at = now}).
     */
    @Transactional
    public int ensureSchedules(Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbcTemplate.update(
                """
                INSERT INTO monitor_schedules (monitor_id, next_run_at, updated_at)
                SELECT m.id, CAST(? AS TIMESTAMPTZ), CAST(? AS TIMESTAMPTZ)
                FROM monitors m
                WHERE m.enabled = TRUE
                  AND NOT EXISTS (
                      SELECT 1 FROM monitor_schedules s WHERE s.monitor_id = m.id
                  )
                """,
                ts,
                ts);
    }

    /**
     * Atomically claims due monitors: advances {@code next_run_at} by the monitor
     * interval and assigns a stable {@code pending_job_id} for SQS publish / retry.
     */
    @Transactional
    public List<DueMonitorJob> claimDueMonitors(Instant now, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Timestamp ts = Timestamp.from(now);
        // Parameter order matches CLAIM_DUE_SQL placeholders:
        // 1) due cutoff timestamptz, 2) limit, 3) pending_scheduled_at,
        // 4) next_run_at base timestamptz, 5) updated_at timestamptz.
        return jdbcTemplate.query(
                CLAIM_DUE_SQL,
                DUE_MAPPER,
                ts,
                limit,
                ts,
                ts,
                ts);
    }

    /**
     * Takes an exclusive short lease on pending (unpublished) jobs so concurrent
     * instances do not publish the same {@code pending_job_id} at once.
     * Expired leases from crashed publishers become claimable again.
     */
    @Transactional
    public List<DueMonitorJob> leasePendingPublishes(Instant now, Instant leaseUntil, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                LEASE_PENDING_SQL,
                DUE_MAPPER,
                Timestamp.from(now),
                limit,
                Timestamp.from(leaseUntil),
                Timestamp.from(now));
    }

    /**
     * Clears the pending claim after a successful SQS publish.
     * Only clears when {@code pending_job_id} still matches (idempotent).
     */
    @Transactional
    public boolean markPublished(UUID monitorId, UUID jobId, Instant publishedAt) {
        Timestamp ts = Timestamp.from(publishedAt);
        int rows = jdbcTemplate.update(
                """
                UPDATE monitor_schedules
                SET pending_job_id = NULL,
                    pending_scheduled_at = NULL,
                    publish_lease_until = NULL,
                    last_enqueued_at = CAST(? AS TIMESTAMPTZ),
                    last_enqueued_job_id = ?,
                    updated_at = CAST(? AS TIMESTAMPTZ)
                WHERE monitor_id = ?
                  AND pending_job_id = ?
                """,
                ts,
                jobId,
                ts,
                monitorId,
                jobId);
        return rows > 0;
    }

    /**
     * Drops a pending claim without recording a successful enqueue
     * (e.g. URL no longer allowed). Interval was already advanced on claim.
     */
    @Transactional
    public boolean abandonPending(UUID monitorId, UUID jobId, Instant at) {
        int rows = jdbcTemplate.update(
                """
                UPDATE monitor_schedules
                SET pending_job_id = NULL,
                    pending_scheduled_at = NULL,
                    publish_lease_until = NULL,
                    updated_at = CAST(? AS TIMESTAMPTZ)
                WHERE monitor_id = ?
                  AND pending_job_id = ?
                """,
                Timestamp.from(at),
                monitorId,
                jobId);
        return rows > 0;
    }

    /**
     * Releases the publish lease after a failed SQS attempt so the next tick can retry.
     * Leaves {@code pending_job_id} intact.
     */
    @Transactional
    public boolean releasePublishLease(UUID monitorId, UUID jobId, Instant at) {
        int rows = jdbcTemplate.update(
                """
                UPDATE monitor_schedules
                SET publish_lease_until = NULL,
                    updated_at = CAST(? AS TIMESTAMPTZ)
                WHERE monitor_id = ?
                  AND pending_job_id = ?
                """,
                Timestamp.from(at),
                monitorId,
                jobId);
        return rows > 0;
    }
}
