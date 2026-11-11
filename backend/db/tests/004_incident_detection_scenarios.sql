-- Manual / CI-optional scenarios for netpulse_record_monitoring_check.
-- Requires migrations 001–004 applied. Does not run automatically in mvn test.
-- Replace :monitor with a real monitors.id or insert a throwaway monitor first.

-- Example setup (edit URL to an approved target if needed):
-- INSERT INTO monitors (name, url, interval_seconds, enabled)
-- VALUES ('incident-test', 'https://example.com', 60, true)
-- RETURNING id;

BEGIN;

-- Use a disposable monitor for the script:
WITH m AS (
    INSERT INTO monitors (name, url, interval_seconds, enabled)
    VALUES ('phase11-incident-fixture', 'https://example.com', 60, true)
    RETURNING id
)
SELECT id AS fixture_monitor FROM m \gset

-- 1) First failure → recorded, no incident
SELECT netpulse_record_monitoring_check(
    gen_random_uuid(), :'fixture_monitor'::uuid, 500, 12.0, false,
    '2026-11-10T22:00:00Z'::timestamptz);

-- 2) Second failure → opened
SELECT netpulse_record_monitoring_check(
    gen_random_uuid(), :'fixture_monitor'::uuid, 500, 13.0, false,
    '2026-11-10T22:01:00Z'::timestamptz);

-- Expect one open incident
SELECT COUNT(*) AS open_incidents
FROM incidents
WHERE monitor_id = :'fixture_monitor'::uuid AND resolved_at IS NULL;

-- 3) Duplicate job id → duplicate (no second open)
SELECT netpulse_record_monitoring_check(
    '11111111-1111-1111-1111-111111111111'::uuid, :'fixture_monitor'::uuid,
    500, 13.0, false, '2026-11-10T22:01:00Z'::timestamptz);
SELECT netpulse_record_monitoring_check(
    '11111111-1111-1111-1111-111111111111'::uuid, :'fixture_monitor'::uuid,
    500, 13.0, false, '2026-11-10T22:01:00Z'::timestamptz);

-- 4) Success → resolved
SELECT netpulse_record_monitoring_check(
    gen_random_uuid(), :'fixture_monitor'::uuid, 200, 10.0, true,
    '2026-11-10T22:02:00Z'::timestamptz);

SELECT COUNT(*) AS still_open
FROM incidents
WHERE monitor_id = :'fixture_monitor'::uuid AND resolved_at IS NULL;

-- 5) Out-of-order older failure after recovery must not reopen
SELECT netpulse_record_monitoring_check(
    gen_random_uuid(), :'fixture_monitor'::uuid, 500, 99.0, false,
    '2026-11-10T21:59:00Z'::timestamptz);

SELECT COUNT(*) AS open_after_stale_failure
FROM incidents
WHERE monitor_id = :'fixture_monitor'::uuid AND resolved_at IS NULL;

ROLLBACK;
