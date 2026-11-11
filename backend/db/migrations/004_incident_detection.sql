-- Phase 11: automatic incident detection on monitoring check insert.
-- Apply in Supabase SQL Editor after 001–003.
-- Why a migration:
--   1) Partial unique index prevents two open incidents per monitor under concurrency.
--   2) PL/pgSQL function keeps check insert + incident open/resolve atomic.

-- At most one unresolved incident per monitor (concurrent workers).
CREATE UNIQUE INDEX IF NOT EXISTS uq_incidents_one_open_per_monitor
    ON incidents (monitor_id)
    WHERE resolved_at IS NULL;

COMMENT ON INDEX uq_incidents_one_open_per_monitor IS
    'Ensures concurrent workers cannot open duplicate incidents for the same monitor';

/*
 * Atomically record a monitoring check and update incident state.
 *
 * Rules (evaluated on checks ordered by checked_at DESC, id DESC):
 *   - Duplicate job_id → no-op (idempotent SQS redelivery)
 *   - Open incident after 2 consecutive latest failures (if none open)
 *   - Resolve open incident after 1 latest success
 *   - Out-of-order inserts cannot flip state using stale rows: decisions
 *     always use the latest checks by checked_at, not insert order
 *
 * Returns: duplicate | recorded | opened | resolved
 */
CREATE OR REPLACE FUNCTION netpulse_record_monitoring_check(
    p_job_id            UUID,
    p_monitor_id        UUID,
    p_http_status       INTEGER,
    p_response_time_ms  DOUBLE PRECISION,
    p_success           BOOLEAN,
    p_checked_at        TIMESTAMPTZ DEFAULT NOW()
) RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
    v_inserted_id     UUID;
    v_open_id         UUID;
    v_open_started    TIMESTAMPTZ;
    v_latest_success  BOOLEAN;
    v_latest_at       TIMESTAMPTZ;
    v_second_success  BOOLEAN;
    v_second_at       TIMESTAMPTZ;
    v_new_incident    UUID;
    v_checked_at      TIMESTAMPTZ := COALESCE(p_checked_at, NOW());
BEGIN
    IF p_job_id IS NULL OR p_monitor_id IS NULL OR p_success IS NULL THEN
        RAISE EXCEPTION 'job_id, monitor_id, and success are required';
    END IF;

    -- Serialize incident decisions per monitor across workers.
    PERFORM 1 FROM monitors WHERE id = p_monitor_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'monitor % not found', p_monitor_id;
    END IF;

    INSERT INTO monitoring_checks (
        job_id, monitor_id, http_status, response_time_ms, success, checked_at
    ) VALUES (
        p_job_id, p_monitor_id, p_http_status, p_response_time_ms, p_success, v_checked_at
    )
    ON CONFLICT (job_id) DO NOTHING
    RETURNING id INTO v_inserted_id;

    IF v_inserted_id IS NULL THEN
        RETURN 'duplicate';
    END IF;

    SELECT c.success, c.checked_at
      INTO v_latest_success, v_latest_at
      FROM monitoring_checks c
     WHERE c.monitor_id = p_monitor_id
     ORDER BY c.checked_at DESC, c.id DESC
     LIMIT 1;

    SELECT c.success, c.checked_at
      INTO v_second_success, v_second_at
      FROM monitoring_checks c
     WHERE c.monitor_id = p_monitor_id
     ORDER BY c.checked_at DESC, c.id DESC
     OFFSET 1
     LIMIT 1;

    SELECT i.id, i.started_at
      INTO v_open_id, v_open_started
      FROM incidents i
     WHERE i.monitor_id = p_monitor_id
       AND i.resolved_at IS NULL
     ORDER BY i.started_at DESC
     LIMIT 1
     FOR UPDATE;

    -- Recovery: latest observation is success → resolve open incident.
    IF v_open_id IS NOT NULL AND v_latest_success IS TRUE THEN
        IF v_latest_at >= v_open_started THEN
            UPDATE incidents
               SET resolved_at = v_latest_at
             WHERE id = v_open_id
               AND resolved_at IS NULL;
            RETURN 'resolved';
        END IF;
        RETURN 'recorded';
    END IF;

    -- Outage: two newest observations are failures and none open.
    IF v_open_id IS NULL
       AND v_latest_success IS FALSE
       AND v_second_success IS FALSE
       AND v_second_at IS NOT NULL THEN
        v_new_incident := NULL; -- RETURNING INTO leaves prior value if 0 rows
        INSERT INTO incidents (monitor_id, started_at, reason)
        VALUES (
            p_monitor_id,
            v_second_at, -- start of the consecutive failure streak
            'Two consecutive failed checks'
        )
        ON CONFLICT (monitor_id) WHERE (resolved_at IS NULL) DO NOTHING
        RETURNING id INTO v_new_incident;

        IF v_new_incident IS NOT NULL THEN
            RETURN 'opened';
        END IF;
        -- Conflict: another session won the partial unique index.
        RETURN 'recorded';
    END IF;

    RETURN 'recorded';
END;
$$;

COMMENT ON FUNCTION netpulse_record_monitoring_check(
    UUID, UUID, INTEGER, DOUBLE PRECISION, BOOLEAN, TIMESTAMPTZ
) IS
    'Idempotent check insert + consecutive-failure incident open/resolve (Phase 11)';
