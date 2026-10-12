-- V9: Session revocation + password-reset throttling.

-- users.session_version: security stamp embedded in JWTs. Bumping it
-- invalidates every previously issued access token for that user (logout,
-- password change/reset, emergency reset, handover).
ALTER TABLE users ADD COLUMN IF NOT EXISTS session_version BIGINT NOT NULL DEFAULT 0;

-- Password reset codes get the same attempt ceiling as MFA challenges.
ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;
ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 5;

-- Rate-limit counters now start at 0 and are incremented explicitly, avoiding
-- the old off-by-one caused by the column default of 1.
ALTER TABLE login_rate_limits ALTER COLUMN attempt_count SET DEFAULT 0;

-- Speeds up reuse detection / family revocation by user.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_revoked ON refresh_tokens(user_id, revoked);
