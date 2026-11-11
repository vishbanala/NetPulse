package com.netpulse.repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.netpulse.analytics.MonitorStatsCalculator;
import com.netpulse.dto.MonitorStatsResponse;
import com.netpulse.model.MonitoringCheck;

@Repository
public class MonitoringCheckRepository {

    private static final RowMapper<MonitoringCheck> ROW_MAPPER = (rs, rowNum) -> {
        MonitoringCheck check = new MonitoringCheck();
        check.setId((UUID) rs.getObject("id"));
        check.setMonitorId((UUID) rs.getObject("monitor_id"));
        int status = rs.getInt("http_status");
        check.setHttpStatus(rs.wasNull() ? null : status);
        double latency = rs.getDouble("response_time_ms");
        check.setResponseTimeMs(rs.wasNull() ? null : latency);
        check.setSuccess(rs.getBoolean("success"));
        Timestamp checkedAt = rs.getTimestamp("checked_at");
        if (checkedAt != null) {
            check.setCheckedAt(OffsetDateTime.ofInstant(checkedAt.toInstant(), ZoneOffset.UTC));
        }
        return check;
    };

    private final JdbcTemplate jdbcTemplate;

    public MonitoringCheckRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MonitoringCheck> findByMonitorId(UUID monitorId) {
        return findByMonitorId(monitorId, 50);
    }

    public List<MonitoringCheck> findByMonitorId(UUID monitorId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, monitor_id, http_status, response_time_ms, success, checked_at
                FROM monitoring_checks
                WHERE monitor_id = ?
                ORDER BY checked_at DESC
                LIMIT ?
                """,
                ROW_MAPPER,
                monitorId,
                limit);
    }

    /**
     * Aggregates check stats for a monitor. Returns zeros / nulls when no rows exist.
     */
    public MonitorStatsResponse aggregateStats(UUID monitorId) {
        return jdbcTemplate.query(
                """
                SELECT
                    COUNT(*) AS total_checks,
                    COUNT(*) FILTER (WHERE success) AS successful_checks,
                    COUNT(*) FILTER (WHERE NOT success) AS failed_checks,
                    AVG(response_time_ms) AS avg_response_time_ms
                FROM monitoring_checks
                WHERE monitor_id = ?
                """,
                rs -> {
                    rs.next();
                    long total = rs.getLong("total_checks");
                    long successful = rs.getLong("successful_checks");
                    long failed = rs.getLong("failed_checks");
                    double avg = rs.getDouble("avg_response_time_ms");
                    Double averageResponseTimeMs = rs.wasNull() ? null : avg;

                    MonitoringCheck latest = findLatest(monitorId);
                    return new MonitorStatsResponse(
                            monitorId,
                            total,
                            successful,
                            failed,
                            MonitorStatsCalculator.uptimePercentage(total, successful),
                            averageResponseTimeMs,
                            latest != null ? latest.getCheckedAt() : null,
                            latest != null ? latest.isSuccess() : null,
                            latest != null ? latest.getHttpStatus() : null);
                },
                monitorId);
    }

    private MonitoringCheck findLatest(UUID monitorId) {
        List<MonitoringCheck> rows = findByMonitorId(monitorId, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
