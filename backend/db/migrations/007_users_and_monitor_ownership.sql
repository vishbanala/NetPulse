-- NetPulse multi-user ownership (additive).
-- Apply manually after backup. Do not store credentials in this file.
--
-- Backfill / admin ownership:
--   1. If monitors already exist and users is empty, INSERT the real bootstrap admin FIRST
--      using NETPULSE_ADMIN_EMAIL + NETPULSE_ADMIN_PASSWORD_HASH from your environment, e.g.:
--        INSERT INTO users (email, password_hash, role, email_verified_at)
--        VALUES ('you@example.com', '$2a$10$YOUR_REAL_HASH...', 'ADMIN', NOW());
--      Then re-run this migration (or the ownership UPDATE below).
--   2. This migration NEVER inserts a default/known password hash.
--   3. AdminUserBootstrap (AUTHENTICATED startup) upserts the same admin from env.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS users (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email              TEXT NOT NULL,
    password_hash      TEXT NOT NULL,
    role               TEXT NOT NULL DEFAULT 'USER',
    email_verified_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT users_email_not_blank CHECK (char_length(trim(email)) > 0),
    CONSTRAINT users_role_valid CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT users_password_hash_bcrypt CHECK (password_hash LIKE '$2%')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_normalized
    ON users (lower(trim(email)));

CREATE INDEX IF NOT EXISTS idx_users_created_at
    ON users (created_at DESC);

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT email_verification_tokens_hash_not_blank CHECK (char_length(trim(token_hash)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_user
    ON email_verification_tokens (user_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_email_verification_tokens_hash
    ON email_verification_tokens (token_hash);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT password_reset_tokens_hash_not_blank CHECK (char_length(trim(token_hash)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user
    ON password_reset_tokens (user_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_password_reset_tokens_hash
    ON password_reset_tokens (token_hash);

ALTER TABLE monitors
    ADD COLUMN IF NOT EXISTS user_id UUID;

DO $$
DECLARE
    admin_id UUID;
    monitor_count INTEGER;
    user_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO monitor_count FROM monitors;
    SELECT COUNT(*) INTO user_count FROM users;

    IF monitor_count > 0 AND user_count = 0 THEN
        RAISE EXCEPTION
            'monitors exist but users is empty: insert bootstrap admin with NETPULSE_ADMIN_EMAIL + NETPULSE_ADMIN_PASSWORD_HASH, then re-run migration 007';
    END IF;

    IF user_count > 0 THEN
        -- Prefer an ADMIN row; otherwise earliest user.
        SELECT id INTO admin_id
        FROM users
        ORDER BY CASE WHEN role = 'ADMIN' THEN 0 ELSE 1 END, created_at ASC
        LIMIT 1;
        UPDATE monitors SET user_id = admin_id WHERE user_id IS NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM monitors WHERE user_id IS NULL) THEN
        RAISE EXCEPTION 'monitors.user_id backfill incomplete: create a users row then re-run ownership update';
    END IF;
END $$;

ALTER TABLE monitors
    ALTER COLUMN user_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'monitors_user_id_fkey'
    ) THEN
        ALTER TABLE monitors
            ADD CONSTRAINT monitors_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_monitors_user_id
    ON monitors (user_id);

CREATE INDEX IF NOT EXISTS idx_monitors_user_created
    ON monitors (user_id, created_at DESC);
