-- NetPulse Step 4 schema
-- Apply in Supabase Dashboard → SQL Editor → New query → Run
-- Do not store credentials in this file.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS monitors (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             TEXT NOT NULL,
    url              TEXT NOT NULL,
    interval_seconds INTEGER NOT NULL,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT monitors_name_not_blank CHECK (char_length(trim(name)) > 0),
    CONSTRAINT monitors_url_http CHECK (url ~* '^https?://'),
    CONSTRAINT monitors_interval_range CHECK (interval_seconds BETWEEN 10 AND 86400)
);

CREATE TABLE IF NOT EXISTS monitoring_checks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    monitor_id       UUID NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
    http_status      INTEGER,
    response_time_ms DOUBLE PRECISION,
    success          BOOLEAN NOT NULL,
    checked_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS incidents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    monitor_id  UUID NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    reason      TEXT,
    CONSTRAINT incidents_resolved_after_start
        CHECK (resolved_at IS NULL OR resolved_at >= started_at)
);

CREATE INDEX IF NOT EXISTS idx_monitors_created_at
    ON monitors (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_monitors_enabled
    ON monitors (enabled);

CREATE INDEX IF NOT EXISTS idx_monitoring_checks_monitor_checked
    ON monitoring_checks (monitor_id, checked_at DESC);

CREATE INDEX IF NOT EXISTS idx_incidents_monitor_started
    ON incidents (monitor_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_incidents_open
    ON incidents (monitor_id)
    WHERE resolved_at IS NULL;
