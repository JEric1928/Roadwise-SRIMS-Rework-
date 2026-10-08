-- V6 Schema alignment with current entities and secure endpoint contracts

-- Align RoadReport table mapping (@Table(name = "reports")) without data loss
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'road_reports')
       AND NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'reports') THEN
        ALTER TABLE road_reports RENAME TO reports;
    END IF;
END $$;

-- CityRoad column alignment
ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS road_id VARCHAR(255);
ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS road_importance VARCHAR(255);
ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS terrain_type VARCHAR(255);
ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS road_type VARCHAR(255);

UPDATE city_roads
SET road_type = COALESCE(NULLIF(road_type, ''), pavement_type)
WHERE pavement_type IS NOT NULL;

UPDATE city_roads
SET road_importance = COALESCE(NULLIF(road_importance, ''), road_classification)
WHERE road_classification IS NOT NULL;

-- RoadReport field/type alignment
ALTER TABLE reports ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS road_importance VARCHAR(255);
ALTER TABLE reports ADD COLUMN IF NOT EXISTS road_type VARCHAR(255);
ALTER TABLE reports ADD COLUMN IF NOT EXISTS terrain_type VARCHAR(255);
ALTER TABLE reports ADD COLUMN IF NOT EXISTS cv_damage_classification VARCHAR(255);
ALTER TABLE reports ADD COLUMN IF NOT EXISTS date_submitted DATE;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS city_road_code VARCHAR(255);

UPDATE reports
SET date_submitted = COALESCE(date_submitted, CAST(created_at AS DATE))
WHERE created_at IS NOT NULL;

UPDATE reports
SET city_road_code = COALESCE(NULLIF(city_road_code, ''), city_road_id::text)
WHERE city_road_id IS NOT NULL;

ALTER TABLE reports
    ALTER COLUMN actual_completion_date TYPE TIMESTAMP WITHOUT TIME ZONE
    USING actual_completion_date::timestamp;

-- ActivityLog alignment while preserving legacy data
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS timestamp TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_id BIGINT;
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_name VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_role VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_office VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS category VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS target_entity VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS status VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS http_method VARCHAR(50);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS user_agent TEXT;

UPDATE activity_logs
SET timestamp = COALESCE(timestamp, created_at),
    actor_id = COALESCE(actor_id, user_id),
    category = COALESCE(NULLIF(category, ''), 'SYSTEM'),
    target_entity = COALESCE(NULLIF(target_entity, ''), 'SYSTEM'),
    description = COALESCE(description, details),
    status = COALESCE(NULLIF(status, ''), 'SUCCESS');

-- SystemSettings entity alignment (single-row + maintenance mode)
ALTER TABLE system_settings ALTER COLUMN setting_key DROP NOT NULL;
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS maintenance_mode BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO system_settings (id, maintenance_mode)
VALUES (1, FALSE)
ON CONFLICT (id) DO NOTHING;

-- Notification recipient alignment with safe backfill
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS recipient_id BIGINT;
UPDATE notifications SET recipient_id = user_id WHERE recipient_id IS NULL AND user_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'notifications' AND constraint_name = 'fk_notifications_recipient'
    ) THEN
        ALTER TABLE notifications
            ADD CONSTRAINT fk_notifications_recipient
            FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE;
    END IF;
END $$;

-- Alignment indexes (reports table and notification/activity query paths)
CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status);
CREATE INDEX IF NOT EXISTS idx_reports_barangay_id ON reports(barangay_id);
CREATE INDEX IF NOT EXISTS idx_reports_severity ON reports(severity);
CREATE INDEX IF NOT EXISTS idx_reports_created_at ON reports(created_at);
CREATE INDEX IF NOT EXISTS idx_reports_user_status ON reports(user_id, status);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created ON notifications(recipient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_unread ON notifications(recipient_id, is_read);
CREATE INDEX IF NOT EXISTS idx_activity_logs_actor_timestamp ON activity_logs(actor_id, timestamp DESC);
