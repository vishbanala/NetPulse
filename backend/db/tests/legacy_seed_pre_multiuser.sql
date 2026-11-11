-- Isolated-test seed: legacy NetPulse shape (no users / no monitors.user_id).
-- Safe fixture data only — not for production.

INSERT INTO monitors (id, name, url, interval_seconds, enabled, created_at)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'Example', 'https://example.com', 60, TRUE, NOW() - INTERVAL '14 days'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 'Httpbin', 'https://httpbin.org/get', 120, TRUE, NOW() - INTERVAL '10 days'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3', 'Paused', 'https://example.org', 30, FALSE, NOW() - INTERVAL '7 days');

INSERT INTO monitoring_checks (monitor_id, http_status, response_time_ms, success, checked_at)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 200, 110.5, TRUE, NOW() - INTERVAL '1 hour'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 500, 900.0, FALSE, NOW() - INTERVAL '50 minutes'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 500, 880.0, FALSE, NOW() - INTERVAL '40 minutes'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 200, 95.0, TRUE, NOW() - INTERVAL '30 minutes');

INSERT INTO incidents (monitor_id, started_at, resolved_at, reason)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', NOW() - INTERVAL '50 minutes', NOW() - INTERVAL '35 minutes', 'HTTP failure'),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', NOW() - INTERVAL '2 days', NULL, 'Timeout');

INSERT INTO monitor_schedules (monitor_id, next_run_at, updated_at)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', NOW() + INTERVAL '1 minute', NOW()),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', NOW() + INTERVAL '2 minutes', NOW());
