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

    public List<Monitor> findAll() {
        return jdbcTemplate.query(
                """
                SELECT id, name, url, interval_seconds, enabled, created_at
                FROM monitors
                ORDER BY created_at DESC
                """,
                ROW_MAPPER);
    }

    public Optional<Monitor> findById(UUID id) {
        List<Monitor> results = jdbcTemplate.query(
                """
                SELECT id, name, url, interval_seconds, enabled, created_at
                FROM monitors
                WHERE id = ?
                """,
                ROW_MAPPER,
                id);
        return results.stream().findFirst();
    }

    public Monitor insert(String name, String url, int intervalSeconds, boolean enabled) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO monitors (name, url, interval_seconds, enabled)
                VALUES (?, ?, ?, ?)
                RETURNING id, name, url, interval_seconds, enabled, created_at
                """,
                ROW_MAPPER,
                name,
                url,
                intervalSeconds,
                enabled);
    }

    public Optional<Monitor> update(UUID id, String name, String url, int intervalSeconds, boolean enabled) {
        List<Monitor> results = jdbcTemplate.query(
                """
                UPDATE monitors
                SET name = ?,
                    url = ?,
                    interval_seconds = ?,
                    enabled = ?
                WHERE id = ?
                RETURNING id, name, url, interval_seconds, enabled, created_at
                """,
                ROW_MAPPER,
                name,
                url,
                intervalSeconds,
                enabled,
                id);
        return results.stream().findFirst();
    }

    public boolean deleteById(UUID id) {
        int rows = jdbcTemplate.update("DELETE FROM monitors WHERE id = ?", id);
        return rows > 0;
    }

    public boolean existsById(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM monitors WHERE id = ?",
                Integer.class,
                id);
        return count != null && count > 0;
    }
}
