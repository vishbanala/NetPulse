-- Step 6: idempotent check inserts keyed by SQS jobId
-- Apply in Supabase SQL Editor (do not store credentials here).

ALTER TABLE monitoring_checks
    ADD COLUMN IF NOT EXISTS job_id UUID;

-- PostgreSQL UNIQUE allows multiple NULLs; non-null job_id values must be unique.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_monitoring_checks_job_id'
    ) THEN
        ALTER TABLE monitoring_checks
            ADD CONSTRAINT uq_monitoring_checks_job_id UNIQUE (job_id);
    END IF;
END $$;

COMMENT ON COLUMN monitoring_checks.job_id IS
    'SQS monitoring job UUID; unique for idempotent worker inserts';
