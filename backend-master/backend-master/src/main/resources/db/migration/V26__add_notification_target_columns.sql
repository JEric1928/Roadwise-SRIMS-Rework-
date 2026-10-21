-- Deep-link target columns for notifications. The frontend bell badge needs to
-- route a notification to the entity it is about (e.g. open the report review
-- modal for a REPORT notification). target_type is a coarse entity kind
-- ("REPORT"), target_id the entity's primary key. Both are nullable: legacy rows
-- and notification types without a deep-link target keep nulls.
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS target_type VARCHAR(50);
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS target_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_notifications_target
    ON notifications (target_type, target_id);
