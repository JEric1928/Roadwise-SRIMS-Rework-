-- V16: Enrolled MFA factors, recovery codes, and server-issued challenge tokens.
--
-- Two problems this addresses:
--
--  1. mfa_challenges was reachable with a client-supplied userId
--     (POST /api/auth/verify-mfa body), so a challenge was never actually bound
--     to the login attempt that created it. Every row now carries a hashed,
--     single-use challenge token that only the server can issue, and the client
--     can only present back. That binding is what makes letting the user *choose*
--     a factor safe: the backend still decides which factors are acceptable.
--
--  2. MFA was email-OTP only. user_mfa_factors records every enrolled factor so
--     a user can satisfy MFA with any single one of them (policy ANY_ONE).
--
-- Additive and safe against existing data: every new column is nullable or has a
-- default, and the default of users.mfa_policy (EMAIL_OTP) reproduces today's
-- "email OTP is the only factor" behaviour for every existing account.

-- ---------------------------------------------------------------
-- Enrolled factors
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_mfa_factors (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    factor_type VARCHAR(32) NOT NULL,               -- EMAIL_OTP | TOTP
    label VARCHAR(100),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',  -- PENDING | ACTIVE | DISABLED

    -- TOTP: the shared secret must be recoverable to recompute codes, so unlike
    -- every other credential in this schema it is encrypted rather than hashed.
    -- AES-256-GCM with the key held in the environment, never in the database.
    -- secret_key_version lets a rotation job re-encrypt without downtime.
    secret_ciphertext TEXT,
    secret_key_version INT,

    -- Replay defence: the RFC 6238 time-step of the most recently accepted code.
    -- A code whose step is <= this value is refused even when it falls inside the
    -- accepted clock-drift window, so an observed code cannot be replayed.
    last_totp_step BIGINT,

    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP WITHOUT TIME ZONE,
    disabled_at TIMESTAMP WITHOUT TIME ZONE
);

-- Column-level repair, because CREATE TABLE IF NOT EXISTS above is a no-op whenever a
-- table of that name already exists -- whatever shape it happens to have. A
-- pre-existing user_mfa_factors without `status` is what made the partial index below
-- abort the chain with
--   ERROR: column "status" does not exist  (SQLSTATE 42703, line 48)
-- and with it every deployment of this build. On a fresh database each ALTER is a
-- no-op; on a drifted one it fills the gaps before anything reads those columns, so
-- the indexes, the FKs from mfa_challenges and the entity mapping all resolve.
-- Types and defaults match the CREATE above so the two paths converge on one schema.
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS id BIGSERIAL;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS factor_type VARCHAR(32);
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS label VARCHAR(100);
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS status VARCHAR(24) NOT NULL DEFAULT 'PENDING';
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS secret_ciphertext TEXT;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS secret_key_version INT;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS last_totp_step BIGINT;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMP WITHOUT TIME ZONE;

-- At most one non-disabled TOTP factor per user. Partial so that disabled rows
-- keep their history without blocking a re-enrolment.
CREATE UNIQUE INDEX IF NOT EXISTS uk_mfa_factors_user_type_active
    ON user_mfa_factors(user_id, factor_type)
    WHERE status <> 'DISABLED';

CREATE INDEX IF NOT EXISTS idx_mfa_factors_user_status
    ON user_mfa_factors(user_id, status);

-- ---------------------------------------------------------------
-- Recovery codes
--
-- Stored hashed like every other credential here. They are the only way back in
-- when a user loses the device holding their second factor, which also makes them
-- equivalent to a password: they are single-use and re-issuing replaces the set.
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mfa_recovery_codes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    used_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Same reasoning as user_mfa_factors above: the index on used_at cannot be built if a
-- pre-existing table carries a different column set.
ALTER TABLE mfa_recovery_codes ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE mfa_recovery_codes ADD COLUMN IF NOT EXISTS code_hash VARCHAR(255);
ALTER TABLE mfa_recovery_codes ADD COLUMN IF NOT EXISTS used_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE mfa_recovery_codes ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_mfa_recovery_codes_user_unused
    ON mfa_recovery_codes(user_id) WHERE used_at IS NULL;

-- ---------------------------------------------------------------
-- users.mfa_policy
--
-- EMAIL_OTP (default) = email OTP is the sole acceptable factor; this is what
-- every existing account already does.
-- ANY_ONE             = the user may satisfy MFA with any single active factor.
-- Kept in sync with actual enrolment by MfaPolicyService.
-- ---------------------------------------------------------------
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_policy VARCHAR(32) NOT NULL DEFAULT 'EMAIL_OTP';

-- ---------------------------------------------------------------
-- Bind challenges to their login attempt
-- ---------------------------------------------------------------
ALTER TABLE mfa_challenges ADD COLUMN IF NOT EXISTS challenge_token_hash VARCHAR(255);
ALTER TABLE mfa_challenges ADD COLUMN IF NOT EXISTS factor_type VARCHAR(32);
ALTER TABLE mfa_challenges ADD COLUMN IF NOT EXISTS factor_id BIGINT
    REFERENCES user_mfa_factors(id) ON DELETE SET NULL;

-- One row per issued token. The token is only ever minted once per row, so
-- uniqueness is a meaningful integrity constraint rather than a formality.
CREATE UNIQUE INDEX IF NOT EXISTS uk_mfa_challenges_token
    ON mfa_challenges(challenge_token_hash) WHERE challenge_token_hash IS NOT NULL;

-- A challenge row is created as soon as the password is accepted, which is
-- before the user has picked a factor -- so there is no OTP to store yet. The
-- column was NOT NULL under the old single-step flow.
ALTER TABLE mfa_challenges ALTER COLUMN otp_code_hash DROP NOT NULL;
