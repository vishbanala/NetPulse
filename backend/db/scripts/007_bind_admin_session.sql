-- Bind bootstrap admin for migration 007 from psql variables (same session).
-- Invoked by Apply-MultiuserMigrations.ps1 — do not put real secrets in this file.
--
-- Expects:
--   psql -v admin_email=... -v admin_hash=... -f 007_bind_admin_session.sql

-- set_config returns the stored value; only surface lengths / hash prefix.
SELECT length(set_config('netpulse.admin_email', :'admin_email', false)) AS admin_email_chars;
SELECT left(set_config('netpulse.admin_password_hash', :'admin_hash', false), 4) AS hash_prefix;
