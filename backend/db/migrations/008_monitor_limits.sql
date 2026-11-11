-- Raise minimum monitor interval to 5 minutes and document per-user active cap (enforced in app TX).
-- Apply in the same PostgreSQL transaction as 007 (see Apply-MultiuserMigrations.ps1).

UPDATE monitors
SET interval_seconds = 300
WHERE interval_seconds < 300;

ALTER TABLE monitors DROP CONSTRAINT IF EXISTS monitors_interval_range;

ALTER TABLE monitors
    ADD CONSTRAINT monitors_interval_range
    CHECK (interval_seconds BETWEEN 300 AND 86400);
