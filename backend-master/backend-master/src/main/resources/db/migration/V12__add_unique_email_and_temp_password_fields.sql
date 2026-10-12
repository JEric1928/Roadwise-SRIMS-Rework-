-- V12: Unique email constraint, temporary password expiry and forced rotation flag.

-- Prevent duplicate email registrations which corrupt lookup queries.
ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email);

-- Password rotation policy and temporary password expiration.
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_change_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS temp_password_expires_at TIMESTAMP WITHOUT TIME ZONE;
