package com.netpulse.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuthTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertEmailVerificationToken(UUID userId, String tokenHash, Instant expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO email_verification_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """,
                userId,
                tokenHash,
                Timestamp.from(expiresAt));
    }

    public void insertPasswordResetToken(UUID userId, String tokenHash, Instant expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """,
                userId,
                tokenHash,
                Timestamp.from(expiresAt));
    }

    public Optional<UUID> consumeEmailVerificationToken(String tokenHash, Instant now) {
        List<UUID> rows = jdbcTemplate.query(
                """
                UPDATE email_verification_tokens
                SET used_at = ?
                WHERE token_hash = ?
                  AND used_at IS NULL
                  AND expires_at > ?
                RETURNING user_id
                """,
                (rs, rowNum) -> (UUID) rs.getObject("user_id"),
                Timestamp.from(now),
                tokenHash,
                Timestamp.from(now));
        return rows.stream().findFirst();
    }

    public Optional<UUID> consumePasswordResetToken(String tokenHash, Instant now) {
        List<UUID> rows = jdbcTemplate.query(
                """
                UPDATE password_reset_tokens
                SET used_at = ?
                WHERE token_hash = ?
                  AND used_at IS NULL
                  AND expires_at > ?
                RETURNING user_id
                """,
                (rs, rowNum) -> (UUID) rs.getObject("user_id"),
                Timestamp.from(now),
                tokenHash,
                Timestamp.from(now));
        return rows.stream().findFirst();
    }

    public void invalidateEmailTokensForUser(UUID userId) {
        jdbcTemplate.update(
                """
                UPDATE email_verification_tokens
                SET used_at = COALESCE(used_at, NOW())
                WHERE user_id = ? AND used_at IS NULL
                """,
                userId);
    }

    public void invalidatePasswordTokensForUser(UUID userId) {
        jdbcTemplate.update(
                """
                UPDATE password_reset_tokens
                SET used_at = COALESCE(used_at, NOW())
                WHERE user_id = ? AND used_at IS NULL
                """,
                userId);
    }
}
