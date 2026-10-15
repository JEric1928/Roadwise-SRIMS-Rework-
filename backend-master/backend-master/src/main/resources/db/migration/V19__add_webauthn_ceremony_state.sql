-- V19: WebAuthn ceremony state.
--
-- Background. A registration or authentication ceremony spans two HTTP requests:
-- one returns the options (which embed a one-time WebAuthn challenge), the other
-- submits the authenticator's response to be verified against those options. The
-- options therefore have to survive between requests.
--
-- Spring's module ships only HttpSession-backed repositories for this
-- (HttpSessionPublicKeyCredential{CreationOptions,RequestOptions}Repository). There is
-- no JDBC, Redis, or Map variant. This application is stateless
-- (SessionCreationPolicy.STATELESS), so there is no session to hold them -- which
-- settles the question: the store is purpose-built here.
--
-- Keyed by the SHA-256 of a cryptographically random ceremony ID, never by username
-- or credential ID. The username is mutable and identifying; the credential ID is
-- supplied by the browser and is for credential lookup after verification, not for
-- addressing the ceremony itself.
--
-- Binding is explicit and separate from the lookup key:
--   REGISTRATION   -> the authenticated user's id and stable opaque user handle.
--   AUTHENTICATION -> the pre-authentication mfa_challenges row (or its token hash).
--
-- Consumption is ISSUED -> CONSUMED with consumed_at, claimed atomically under a row
-- lock: a flag checked without locking lets two concurrent requests replay one
-- ceremony. After consumption the options payload is cleared; only type, bindings,
-- timestamps and outcome are retained for a short audit window, then purged. Full
-- security events are recorded separately in activity_logs, not by keeping payloads.

CREATE TABLE IF NOT EXISTS webauthn_ceremony_state (
    ceremony_key_hash VARCHAR(255) PRIMARY KEY,
    ceremony_type VARCHAR(32) NOT NULL,   -- REGISTRATION | AUTHENTICATION
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    user_handle BYTEA,
    mfa_challenge_id BIGINT REFERENCES mfa_challenges(id) ON DELETE CASCADE,
    options_json TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'ISSUED',  -- ISSUED | CONSUMED
    outcome VARCHAR(32),                  -- e.g. COMPLETED | EXPIRED | REPLAYED
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ceremony_binding CHECK (
        (ceremony_type = 'REGISTRATION' AND user_id IS NOT NULL)
        OR (ceremony_type = 'AUTHENTICATION' AND mfa_challenge_id IS NOT NULL)
    )
);

-- Finding a user's live registration ceremony, and a challenge's live assertion
-- ceremony, without scanning consumed history.
CREATE INDEX IF NOT EXISTS idx_ceremony_user_live
    ON webauthn_ceremony_state(user_id) WHERE status = 'ISSUED';
CREATE INDEX IF NOT EXISTS idx_ceremony_challenge_live
    ON webauthn_ceremony_state(mfa_challenge_id) WHERE status = 'ISSUED';
-- Purging consumed/expired rows past the audit window.
CREATE INDEX IF NOT EXISTS idx_ceremony_expiry
    ON webauthn_ceremony_state(expires_at) WHERE status = 'CONSUMED';
