-- Phase 7: durable per-monitor scheduling state for multi-instance backends.
-- Apply in Supabase Dashboard → SQL Editor → New query → Run
-- Do not store credentials in this file.

CREATE TABLE IF NOT EXISTS monitor_schedules (
    monitor_id           UUID PRIMARY KEY REFERENCES monitors (id) ON DELETE CASCADE,
    next_run_at          TIMESTAMPTZ NOT NULL,
    pending_job_id       UUID,
    pending_scheduled_at TIMESTAMPTZ,
    publish_lease_until  TIMESTAMPTZ,
    last_enqueued_at     TIMESTAMPTZ,
    last_enqueued_job_id UUID,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT monitor_schedules_pending_pair CHECK (
        (pending_job_id IS NULL AND pending_scheduled_at IS NULL)
        OR (pending_job_id IS NOT NULL AND pending_scheduled_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_monitor_schedules_due
    ON monitor_schedules (next_run_at)
    WHERE pending_job_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_monitor_schedules_pending
    ON monitor_schedules (pending_scheduled_at)
    WHERE pending_job_id IS NOT NULL;

COMMENT ON TABLE monitor_schedules IS
    'Per-monitor schedule lease: next_run_at advances on claim; pending_* holds an unpublished SQS job for retry.';

COMMENT ON COLUMN monitor_schedules.pending_job_id IS
    'Non-null means a job was claimed for this interval but SQS publish is not yet confirmed.';

COMMENT ON COLUMN monitor_schedules.publish_lease_until IS
    'Exclusive publish attempt lease so multiple backends do not send the same pending job concurrently.';
