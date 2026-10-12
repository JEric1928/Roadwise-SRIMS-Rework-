-- V10: Server-enforced session lifetime on refresh tokens.

-- session_expires_at: absolute lifetime of the login session. Refresh-token
-- rotation must not extend it (previously every rotation granted another 7 days).
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS session_expires_at TIMESTAMP WITHOUT TIME ZONE;

-- last_used_at: last successful refresh. Used to enforce an inactivity window.
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP WITHOUT TIME ZONE;

-- Backfill existing rows so running sessions keep working until their current
-- token expiry rather than being revoked by the migration itself.
UPDATE refresh_tokens SET session_expires_at = COALESCE(session_expires_at, expires_at) WHERE session_expires_at IS NULL;
UPDATE refresh_tokens SET last_used_at = COALESCE(last_used_at, created_at) WHERE last_used_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_session_expires ON refresh_tokens(session_expires_at);
