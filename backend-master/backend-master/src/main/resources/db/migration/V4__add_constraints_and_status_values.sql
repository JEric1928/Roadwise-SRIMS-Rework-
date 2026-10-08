-- V4 Constraints & Status Values

-- Ensure unique constraints where needed
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'unique_user_email'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT unique_user_email UNIQUE (email);
    END IF;
END $$;
