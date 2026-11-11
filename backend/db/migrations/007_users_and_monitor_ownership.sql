-- NetPulse multi-user ownership (additive).
-- Apply manually after backup. Do not store credentials in this file.
--
-- Single-pass admin bootstrap when monitors already exist and users is empty:
--   In the SAME database session, before this file, bind admin credentials from env
--   (never commit the values), e.g. via backend/db/scripts/Apply-MultiuserMigrations.ps1
--   or:
--     SELECT set_config('netpulse.admin_email', '<from NETPULSE_ADMIN_EMAIL>', false);
--     SELECT set_config('netpulse.admin_password_hash', '<from NETPULSE_ADMIN_PASSWORD_HASH>', false);
--
-- This migration NEVER embeds a default/known password hash.
-- AdminUserBootstrap (AUTHENTICATED startup) re-upserts the same admin from env.
--
-- Apply inside a single PostgreSQL transaction with 008 (see Apply-MultiuserMigrations.ps1)
-- so any failure rolls back all DDL and DML from this upgrade.

-- Validate bootstrap admin BEFORE any schema changes when legacy monitors exist.
DO $$
DECLARE
    monitor_count INTEGER;
    users_exist BOOLEAN;
    admin_email TEXT;
    admin_hash TEXT;
BEGIN
    SELECT COUNT(*) INTO monitor_count FROM monitors;
    SELECT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'users'
    ) INTO users_exist;

    IF monitor_count > 0 AND NOT users_exist THEN
        admin_email := nullif(lower(trim(current_setting('netpulse.admin_email', true))), '');
        admin_hash := nullif(trim(current_setting('netpulse.admin_password_hash', true)), '');

        IF admin_email IS NULL OR admin_hash IS NULL THEN
            RAISE EXCEPTION
                'Existing monitors require a bootstrap admin in this session BEFORE schema changes. Set netpulse.admin_email and netpulse.admin_password_hash (BCrypt from NETPULSE_ADMIN_*) via set_config or Apply-MultiuserMigrations.ps1. No default password is embedded in this migration.';
        END IF;

        IF position('@' IN admin_email) < 2 THEN
            RAISE EXCEPTION
                'netpulse.admin_email must look like an email address before schema changes.';
        END IF;

        IF admin_hash !~ '^\$2[aby]?\$\d{2}\$[./A-Za-z0-9]{53}$' THEN
            RAISE EXCEPTION
                'netpulse.admin_password_hash is not a valid BCrypt hash before schema changes.';
        END IF;
    END IF;
END $$;

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
    admin_email TEXT;
    admin_hash TEXT;
BEGIN
    SELECT COUNT(*) INTO monitor_count FROM monitors;
    SELECT COUNT(*) INTO user_count FROM users;

    IF monitor_count > 0 AND user_count = 0 THEN
        admin_email := nullif(lower(trim(current_setting('netpulse.admin_email', true))), '');
        admin_hash := nullif(trim(current_setting('netpulse.admin_password_hash', true)), '');

        IF admin_email IS NULL OR admin_hash IS NULL
           OR admin_hash !~ '^\$2[aby]?\$\d{2}\$[./A-Za-z0-9]{53}$' THEN
            RAISE EXCEPTION
                'Existing monitors require a bootstrap admin in this session. Before re-running 007, set netpulse.admin_email and netpulse.admin_password_hash (BCrypt from NETPULSE_ADMIN_*) via set_config or Apply-MultiuserMigrations.ps1. No default password is embedded in this migration.';
        END IF;

        INSERT INTO users (email, password_hash, role, email_verified_at)
        VALUES (admin_email, admin_hash, 'ADMIN', NOW())
        RETURNING id INTO admin_id;

        UPDATE monitors SET user_id = admin_id WHERE user_id IS NULL;
    ELSIF user_count > 0 THEN
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
        RAISE EXCEPTION 'monitors.user_id backfill incomplete: bootstrap admin and re-run ownership update';
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
