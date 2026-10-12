-- V11: Credential-change session revocation, user optimistic locking, and
-- email-change verification.

-- Optimistic locking on users: prevents a stale save (e.g. slow avatar upload)
-- from silently overwriting a newer password/status/session stamp.
ALTER TABLE users ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Pending (unverified) email change: the new address only becomes active after
-- an OTP is confirmed against it; the old address keeps receiving mail until then.
ALTER TABLE users ADD COLUMN IF NOT EXISTS pending_email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_otp_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_otp_expires_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_otp_attempts INT NOT NULL DEFAULT 0;

-- Bind refresh tokens to the session version they were issued under so a token
-- minted before a credential change can never refresh afterwards, even if the
-- revocation sweep missed it.
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS session_version BIGINT NOT NULL DEFAULT 0;
