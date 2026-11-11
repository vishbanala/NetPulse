package com.netpulse.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.netpulse.model.UserAccount;

@Repository
public class UserRepository {

    private static final RowMapper<UserAccount> ROW_MAPPER = (rs, rowNum) -> {
        UserAccount user = new UserAccount();
        user.setId((UUID) rs.getObject("id"));
        user.setEmail(rs.getString("email"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setRole(rs.getString("role"));
        Timestamp verified = rs.getTimestamp("email_verified_at");
        if (verified != null) {
            user.setEmailVerifiedAt(OffsetDateTime.ofInstant(verified.toInstant(), ZoneOffset.UTC));
        }
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            user.setCreatedAt(OffsetDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC));
        }
        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) {
            user.setUpdatedAt(OffsetDateTime.ofInstant(updated.toInstant(), ZoneOffset.UTC));
        }
        return user;
    };

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<UserAccount> findById(UUID id) {
        List<UserAccount> rows = jdbcTemplate.query(
                """
                SELECT id, email, password_hash, role, email_verified_at, created_at, updated_at
                FROM users WHERE id = ?
                """,
                ROW_MAPPER,
                id);
        return rows.stream().findFirst();
    }

    public Optional<UserAccount> findByEmailNormalized(String email) {
        String normalized = normalizeEmail(email);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        List<UserAccount> rows = jdbcTemplate.query(
                """
                SELECT id, email, password_hash, role, email_verified_at, created_at, updated_at
                FROM users WHERE lower(trim(email)) = ?
                """,
                ROW_MAPPER,
                normalized);
        return rows.stream().findFirst();
    }

    public boolean existsByEmailNormalized(String email) {
        String normalized = normalizeEmail(email);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE lower(trim(email)) = ?",
                Integer.class,
                normalized);
        return count != null && count > 0;
    }

    public UserAccount insert(String email, String passwordHash, String role, Instant verifiedAt) {
        String normalized = normalizeEmail(email);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, role, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id, email, password_hash, role, email_verified_at, created_at, updated_at
                """,
                ROW_MAPPER,
                normalized,
                passwordHash,
                role,
                verifiedAt == null ? null : Timestamp.from(verifiedAt));
    }

    public Optional<UserAccount> upsertAdmin(String email, String passwordHash) {
        String normalized = normalizeEmail(email);
        Optional<UserAccount> existing = findByEmailNormalized(normalized);
        if (existing.isPresent()) {
            jdbcTemplate.update(
                    """
                    UPDATE users
                    SET password_hash = ?, role = 'ADMIN', email_verified_at = COALESCE(email_verified_at, NOW()),
                        updated_at = NOW()
                    WHERE id = ?
                    """,
                    passwordHash,
                    existing.get().getId());
            return findById(existing.get().getId());
        }
        return Optional.ofNullable(insert(normalized, passwordHash, UserAccount.ROLE_ADMIN, Instant.now()));
    }

    public boolean markEmailVerified(UUID userId) {
        int rows = jdbcTemplate.update(
                """
                UPDATE users
                SET email_verified_at = COALESCE(email_verified_at, NOW()), updated_at = NOW()
                WHERE id = ?
                """,
                userId);
        return rows > 0;
    }

    public boolean updatePasswordHash(UUID userId, String passwordHash) {
        int rows = jdbcTemplate.update(
                """
                UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ?
                """,
                passwordHash,
                userId);
        return rows > 0;
    }

    public Optional<UserAccount> lockById(UUID userId) {
        List<UserAccount> rows = jdbcTemplate.query(
                """
                SELECT id, email, password_hash, role, email_verified_at, created_at, updated_at
                FROM users WHERE id = ? FOR UPDATE
                """,
                ROW_MAPPER,
                userId);
        return rows.stream().findFirst();
    }
}
