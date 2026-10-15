-- V18: Step-up authentication for sensitive factor changes.
--
-- Background. /api/auth/mfa/factors/** and /api/auth/mfa/recovery-codes let a
-- signed-in user add an authentication factor or replace their recovery codes.
-- Being authenticated is not sufficient authorisation for that. An access token lives
-- 15 minutes, but /refresh rotates it for the session's absolute lifetime (8 hours), so
-- a stolen token alone is enough to silently enrol an attacker-controlled factor and
-- keep access independent of the password.
--
-- Closing that needs a durable record of when the human last actually proved who they
-- were. It cannot be the token's own issue time, because refresh moves that, and it
-- cannot be the refresh row's created_at, because rotation rewrites that too.
ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS authenticated_at TIMESTAMP WITHOUT TIME ZONE;

-- Deliberately NOT backfilled from created_at. For a rotated row created_at is the
-- rotation moment, not the login moment, so a backfill would grant existing sessions a
-- freshness they cannot be shown to have had. NULL means "never elevated", which forces
-- one re-authentication before any factor change -- the fail-closed direction.

COMMENT ON COLUMN refresh_tokens.authenticated_at IS
    'When the human last authenticated for this session. Set at login, inherited on rotation. NULL = never elevated.';
