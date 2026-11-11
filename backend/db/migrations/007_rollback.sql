-- Manual rollback for 007_users_and_monitor_ownership.sql
-- WARNING: drops user ownership. Existing monitors remain but lose user_id.

ALTER TABLE monitors DROP CONSTRAINT IF EXISTS monitors_user_id_fkey;
DROP INDEX IF EXISTS idx_monitors_user_created;
DROP INDEX IF EXISTS idx_monitors_user_id;
ALTER TABLE monitors DROP COLUMN IF EXISTS user_id;

DROP TABLE IF EXISTS password_reset_tokens;
DROP TABLE IF EXISTS email_verification_tokens;
DROP TABLE IF EXISTS users;
