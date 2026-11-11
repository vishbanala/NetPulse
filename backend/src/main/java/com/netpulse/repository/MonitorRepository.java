package com.netpulse.repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.netpulse.model.Monitor;

@Repository
public class MonitorRepository {

    private static final RowMapper<Monitor> ROW_MAPPER = (rs, rowNum) -> {
        Monitor monitor = new Monitor();
        monitor.setId((UUID) rs.getObject("id"));
        monitor.setUserId((UUID) rs.getObject("user_id"));
        monitor.setName(rs.getString("name"));
        monitor.setUrl(rs.getString("url"));
        monitor.setIntervalSeconds(rs.getInt("interval_seconds"));
        monitor.setEnabled(rs.getBoolean("enabled"));
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            monitor.setCreatedAt(OffsetDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC));
        }
        return monitor;
    };

    private final JdbcTemplate jdbcTemplate;

    public MonitorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Monitor> findAllByUserId(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, user_id, name, url, interval_seconds, enabled, created_at
                FROM monitors
                WHERE user_id = ?
                ORDER BY created_at DESC
                """,
                ROW_MAPPER,
                userId);
    }

    public Optional<Monitor> findByIdAndUserId(UUID id, UUID userId) {
        List<Monitor> results = jdbcTemplate.query(
                """
                SELECT id, user_id, name, url, interval_seconds, enabled, created_at
                FROM monitors
                WHERE id = ? AND user_id = ?
                """,
                ROW_MAPPER,
                id,
                userId);
        return results.stream().findFirst();
    }

    public Monitor insert(UUID userId, String name, String url, int intervalSeconds, boolean enabled) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO monitors (user_id, name, url, interval_seconds, enabled)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, user_id, name, url, interval_seconds, enabled, created_at
                """,
                ROW_MAPPER,
                userId,
                name,
                url,
                intervalSeconds,
                enabled);
    }

    public Optional<Monitor> update(UUID id, UUID userId, String name, String url, int intervalSeconds, boolean enabled) {
        List<Monitor> results = jdbcTemplate.query(
                """
                UPDATE monitors
                SET name = ?,
                    url = ?,
                    interval_seconds = ?,
                    enabled = ?
                WHERE id = ? AND user_id = ?
                RETURNING id, user_id, name, url, interval_seconds, enabled, created_at
                """,
                ROW_MAPPER,
                name,
                url,
                intervalSeconds,
                enabled,
                id,
                userId);
        return results.stream().findFirst();
    }

    public boolean deleteByIdAndUserId(UUID id, UUID userId) {
        int rows = jdbcTemplate.update(
                "DELETE FROM monitors WHERE id = ? AND user_id = ?",
                id,
                userId);
        return rows > 0;
    }

    public boolean existsByIdAndUserId(UUID id, UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monitors WHERE id = ? AND user_id = ?",
                Integer.class,
                id,
                userId);
        return count != null && count > 0;
    }

    public int countEnabledByUserId(UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monitors WHERE user_id = ? AND enabled = TRUE",
                Integer.class,
                userId);
        return count == null ? 0 : count;
    }

    public int countEnabledGlobal() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monitors WHERE enabled = TRUE",
                Integer.class);
        return count == null ? 0 : count;
    }

    /** @deprecated global listing retained only for migration-era tests; prefer findAllByUserId */
    public List<Monitor> findAll() {
        return jdbcTemplate.query(
                """
                SELECT id, user_id, name, url, interval_seconds, enabled, created_at
                FROM monitors
                ORDER BY created_at DESC
                """,
                ROW_MAPPER);
    }
}
