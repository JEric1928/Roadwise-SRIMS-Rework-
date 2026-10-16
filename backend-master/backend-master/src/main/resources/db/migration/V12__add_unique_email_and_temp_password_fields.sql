-- V12: Unique email constraint, temporary password expiry and forced rotation flag.

-- Prevent duplicate email registrations which corrupt lookup queries. Guarded: a
-- database that already carries the constraint must be able to re-run this migration
-- (Flyway replays the chain when it adopts a schema that has no history), and V4's
-- unique_user_email may already cover the same column.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'uk_users_email'
                     AND conrelid = 'users'::regclass) THEN
        ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email);
    END IF;
END $$;

-- Password rotation policy and temporary password expiration.
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_change_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS temp_password_expires_at TIMESTAMP WITHOUT TIME ZONE;
