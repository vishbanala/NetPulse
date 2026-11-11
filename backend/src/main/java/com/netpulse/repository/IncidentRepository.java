package com.netpulse.repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.netpulse.model.Incident;

@Repository
public class IncidentRepository {

    private static final RowMapper<Incident> ROW_MAPPER = (rs, rowNum) -> {
        Incident incident = new Incident();
        incident.setId((UUID) rs.getObject("id"));
        incident.setMonitorId((UUID) rs.getObject("monitor_id"));
        Timestamp started = rs.getTimestamp("started_at");
        if (started != null) {
            incident.setStartedAt(OffsetDateTime.ofInstant(started.toInstant(), ZoneOffset.UTC));
        }
        Timestamp resolved = rs.getTimestamp("resolved_at");
        if (resolved != null) {
            incident.setResolvedAt(OffsetDateTime.ofInstant(resolved.toInstant(), ZoneOffset.UTC));
        }
        incident.setReason(rs.getString("reason"));
        return incident;
    };

    private final JdbcTemplate jdbcTemplate;

    public IncidentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Incident> findByMonitorId(UUID monitorId) {
        return jdbcTemplate.query(
                """
                SELECT id, monitor_id, started_at, resolved_at, reason
                FROM incidents
                WHERE monitor_id = ?
                ORDER BY started_at DESC
                """,
                ROW_MAPPER,
                monitorId);
    }
}
