-- V17: Passkey (WebAuthn) support.
--
-- Passkeys are modelled as one more factor_type on user_mfa_factors rather than a
-- parallel identity store. That is deliberate: the ANY_ONE policy, the challenge
-- binding, factor enumeration and the recovery-code escape hatch all already work
-- over that table, so a passkey inherits them instead of needing a second code path
-- that could drift out of step. Only credential material and the user-handle mapping
-- are new.
--
-- The Spring Security module ships its own JDBC repositories (user_entities /
-- user_credentials). They are not used here: a second credential store alongside
-- user_mfa_factors would mean two sources of truth for "what factors does this user
-- have", and MfaPolicyService would only see half of them.

-- WebAuthn requires an opaque user handle: at most 64 bytes, stable for the life of
-- the account, and with no identifying information in it. A random 32-byte value is
-- generated once and kept here rather than derived from users.id, so the sequential
-- account id is never handed to an authenticator or a sync provider.
CREATE TABLE IF NOT EXISTS webauthn_user_handles (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    user_handle BYTEA NOT NULL UNIQUE,
    display_name VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Credential material for factor_type = 'WEBAUTHN'.
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS credential_id BYTEA;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS public_key BYTEA;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS signature_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS uv_initialized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS backup_eligible BOOLEAN;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS backup_state BOOLEAN;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS transports VARCHAR(255);
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS attestation_object BYTEA;
ALTER TABLE user_mfa_factors ADD COLUMN IF NOT EXISTS attestation_client_data_json BYTEA;

-- A user legitimately holds several passkeys (laptop, phone, hardware key), so
-- WEBAUTHN is exempt from the one-active-factor-per-type rule that TOTP keeps.
DROP INDEX IF EXISTS uk_mfa_factors_user_type_active;
CREATE UNIQUE INDEX IF NOT EXISTS uk_mfa_factors_user_type_active
    ON user_mfa_factors(user_id, factor_type)
    WHERE status <> 'DISABLED' AND factor_type <> 'WEBAUTHN';

-- A credential id identifies exactly one row, which is what makes assertion lookup
-- unambiguous. Disabling a passkey clears this column (see MfaFactorService), so a
-- disabled row never shadows a later re-registration of the same authenticator.
CREATE UNIQUE INDEX IF NOT EXISTS uk_mfa_factors_credential_id
    ON user_mfa_factors(credential_id) WHERE credential_id IS NOT NULL;
