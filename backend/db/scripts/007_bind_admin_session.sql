-- Bind bootstrap admin for migration 007 from psql variables (same session).
-- Invoked by Apply-MultiuserMigrations.ps1 — do not put real secrets in this file.
--
-- Expects:
--   psql -v admin_email=... -v admin_hash=... -f 007_bind_admin_session.sql
--
-- Validates credentials before any schema migration DDL runs.
-- Only surfaces lengths / hash prefix — never the email or full hash.

-- Store session GUCs from psql -v (is_local=false so they survive within this session).
SELECT length(set_config('netpulse.admin_email', :'admin_email', false)) AS admin_email_chars;
SELECT left(set_config('netpulse.admin_password_hash', :'admin_hash', false), 4) AS hash_prefix;

-- Fail fast before 007/008 DDL if bind values are unusable.
DO $$
DECLARE
    admin_email TEXT := nullif(lower(trim(current_setting('netpulse.admin_email', true))), '');
    admin_hash TEXT := nullif(trim(current_setting('netpulse.admin_password_hash', true)), '');
BEGIN
    IF admin_email IS NULL THEN
        RAISE EXCEPTION
            'netpulse.admin_email is missing or blank. Set NETPULSE_ADMIN_EMAIL / -v admin_email before migrations.';
    END IF;

    IF position('@' IN admin_email) < 2 THEN
        RAISE EXCEPTION
            'netpulse.admin_email must look like an email address (contains @).';
    END IF;

    IF admin_hash IS NULL THEN
        RAISE EXCEPTION
            'netpulse.admin_password_hash is missing or blank. Set NETPULSE_ADMIN_PASSWORD_HASH / -v admin_hash before migrations.';
    END IF;

    -- Spring BCryptPasswordEncoder hashes are $2a$ / $2b$ / $2y$ with cost and 53-char payload.
    IF admin_hash !~ '^\$2[aby]?\$\d{2}\$[./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION
            'netpulse.admin_password_hash is not a valid BCrypt hash (expected $2a$/$2b$/$2y$ with cost and 53-char salt+hash).';
    END IF;
END $$;
