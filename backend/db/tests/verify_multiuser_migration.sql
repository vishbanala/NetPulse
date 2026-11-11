-- Post 007/008 verification for isolated legacy migration test.
\echo === counts ===
SELECT 'monitors' AS entity, COUNT(*)::bigint AS n FROM monitors
UNION ALL SELECT 'monitoring_checks', COUNT(*) FROM monitoring_checks
UNION ALL SELECT 'incidents', COUNT(*) FROM incidents
UNION ALL SELECT 'monitor_schedules', COUNT(*) FROM monitor_schedules
UNION ALL SELECT 'users', COUNT(*) FROM users;

\echo === ownership ===
SELECT COUNT(*) AS null_owners FROM monitors WHERE user_id IS NULL;
SELECT u.email, u.role, COUNT(m.*) AS owned
FROM monitors m
JOIN users u ON u.id = m.user_id
GROUP BY u.email, u.role;

\echo === interval floor ===
SELECT COUNT(*) AS below_300 FROM monitors WHERE interval_seconds < 300;
SELECT id, name, interval_seconds, enabled FROM monitors ORDER BY name;

\echo === fk ===
SELECT conname FROM pg_constraint WHERE conname = 'monitors_user_id_fkey';
