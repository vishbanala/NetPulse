-- Phase 12.1: transactional Discord alert outbox.
-- Apply in Supabase SQL Editor after 001–004.
--
-- Why a migration:
--   1) alert_outbox stores notification intents in the same TX as incident open/resolve.
--   2) Replaces netpulse_record_monitoring_check to enqueue outbox rows without
--      changing incident detection rules or check persistence semantics.
--
-- Discord delivery is performed asynchronously by the Spring Boot dispatcher.
-- A crash after Discord accepts a message but before sent_at is recorded can
-- cause one duplicate webhook delivery (at-least-once to Discord).

CREATE TABLE IF NOT EXISTS alert_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id     UUID NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    monitor_id      UUID NOT NULL REFERENCES monitors (id) ON DELETE CASCADE,
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    available_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    max_attempts    INTEGER NOT NULL DEFAULT 8,
    lease_until     TIMESTAMPTZ,
    last_error      TEXT,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT alert_outbox_event_type_check
        CHECK (event_type IN ('incident_opened', 'incident_resolved')),
    CONSTRAINT alert_outbox_attempts_check
        CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts + 1),
    CONSTRAINT alert_outbox_unique_event
        UNIQUE (incident_id, event_type)
);

CREATE INDEX IF NOT EXISTS idx_alert_outbox_pending
    ON alert_outbox (available_at)
    WHERE sent_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_alert_outbox_lease
    ON alert_outbox (lease_until)
    WHERE sent_at IS NULL AND lease_until IS NOT NULL;

COMMENT ON TABLE alert_outbox IS
    'Transactional outbox for Discord incident notifications (Phase 12.1)';
COMMENT ON COLUMN alert_outbox.payload IS
    'Denormalized monitor/event fields for webhook rendering; no secrets';
COMMENT ON CONSTRAINT alert_outbox_unique_event ON alert_outbox IS
    'Exactly one outbox event per incident open/resolve (idempotent enqueue)';

/*
 * Same incident rules as migration 004, plus outbox inserts on open/resolve.
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
    v_monitor_name    TEXT;
    v_monitor_url     TEXT;
    v_duration_secs   BIGINT;
    v_checked_at      TIMESTAMPTZ := COALESCE(p_checked_at, NOW());
BEGIN
    IF p_job_id IS NULL OR p_monitor_id IS NULL OR p_success IS NULL THEN
        RAISE EXCEPTION 'job_id, monitor_id, and success are required';
    END IF;

    SELECT m.name, m.url
      INTO v_monitor_name, v_monitor_url
      FROM monitors m
     WHERE m.id = p_monitor_id
     FOR UPDATE;
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

            v_duration_secs := GREATEST(
                0,
                FLOOR(EXTRACT(EPOCH FROM (v_latest_at - v_open_started)))::BIGINT
            );

            INSERT INTO alert_outbox (incident_id, monitor_id, event_type, payload)
            VALUES (
                v_open_id,
                p_monitor_id,
                'incident_resolved',
                jsonb_build_object(
                    'monitorName', v_monitor_name,
                    'monitorUrl', v_monitor_url,
                    'eventType', 'incident_resolved',
                    'eventAt', v_latest_at,
                    'startedAt', v_open_started,
                    'resolvedAt', v_latest_at,
                    'durationSeconds', v_duration_secs
                )
            )
            ON CONFLICT (incident_id, event_type) DO NOTHING;

            RETURN 'resolved';
        END IF;
        RETURN 'recorded';
    END IF;

    -- Outage: two newest observations are failures and none open.
    IF v_open_id IS NULL
       AND v_latest_success IS FALSE
       AND v_second_success IS FALSE
       AND v_second_at IS NOT NULL THEN
        v_new_incident := NULL;
        INSERT INTO incidents (monitor_id, started_at, reason)
        VALUES (
            p_monitor_id,
            v_second_at,
            'Two consecutive failed checks'
        )
        ON CONFLICT (monitor_id) WHERE (resolved_at IS NULL) DO NOTHING
        RETURNING id INTO v_new_incident;

        IF v_new_incident IS NOT NULL THEN
            INSERT INTO alert_outbox (incident_id, monitor_id, event_type, payload)
            VALUES (
                v_new_incident,
                p_monitor_id,
                'incident_opened',
                jsonb_build_object(
                    'monitorName', v_monitor_name,
                    'monitorUrl', v_monitor_url,
                    'eventType', 'incident_opened',
                    'eventAt', v_second_at,
                    'startedAt', v_second_at
                )
            )
            ON CONFLICT (incident_id, event_type) DO NOTHING;

            RETURN 'opened';
        END IF;
        RETURN 'recorded';
    END IF;

    RETURN 'recorded';
END;
$$;

COMMENT ON FUNCTION netpulse_record_monitoring_check(
    UUID, UUID, INTEGER, DOUBLE PRECISION, BOOLEAN, TIMESTAMPTZ
) IS
    'Check insert + incident open/resolve + alert_outbox enqueue (Phase 11/12.1)';
