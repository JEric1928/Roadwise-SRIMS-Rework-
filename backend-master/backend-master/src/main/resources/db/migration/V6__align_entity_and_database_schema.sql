-- V6: Align database schema with JPA entity mappings (Option A: keep entity fields, add DB columns)
-- Idempotent and safe against both an empty database and a copy of the existing production schema.

-- =========================================================
-- 1. barangays: entity uses barangay_name / brgy_captain / contact_number / email_address
--    Baseline only had "name". Add missing columns, backfill from name, then drop legacy column.
-- =========================================================
ALTER TABLE barangays ADD COLUMN IF NOT EXISTS barangay_name VARCHAR(255);
UPDATE barangays SET barangay_name = name WHERE barangay_name IS NULL;
ALTER TABLE barangays ALTER COLUMN barangay_name SET NOT NULL;
ALTER TABLE barangays DROP COLUMN IF EXISTS name;

ALTER TABLE barangays ADD COLUMN IF NOT EXISTS brgy_captain VARCHAR(255);
ALTER TABLE barangays ADD COLUMN IF NOT EXISTS contact_number VARCHAR(50);
ALTER TABLE barangays ADD COLUMN IF NOT EXISTS email_address VARCHAR(255);

-- =========================================================
-- 2. city_roads: entity uses road_id / road_importance / terrain_type / road_type
--    Keep baseline columns (pavement_type, road_classification, traffic_level, ...) intact;
--    add the new columns and backfill them from the legacy data so nothing is lost.
-- =========================================================
ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS road_id VARCHAR(100);
ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS road_importance VARCHAR(100);
ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS terrain_type VARCHAR(100);
ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS road_type VARCHAR(100);

UPDATE city_roads SET road_importance = road_classification WHERE road_importance IS NULL AND road_classification IS NOT NULL;
UPDATE city_roads SET road_type       = pavement_type       WHERE road_type       IS NULL AND pavement_type       IS NOT NULL;
UPDATE city_roads SET terrain_type    = traffic_level       WHERE terrain_type    IS NULL AND traffic_level       IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_city_roads_barangay_id ON city_roads(barangay_id);

-- =========================================================
-- 3. road_reports: entity snapshot + tracking columns
-- =========================================================
-- Optimistic locking (@Version on RoadReport)
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS city_road_code VARCHAR(100);
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS road_importance VARCHAR(100);
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS road_type VARCHAR(100);
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS terrain_type VARCHAR(100);
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS cv_damage_classification VARCHAR(100);
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS date_submitted DATE;

-- Backfill date_submitted for pre-existing rows so sorting/pagination is stable.
UPDATE road_reports SET date_submitted = created_at::date WHERE date_submitted IS NULL;

-- Index supporting the default list ordering (sort=dateSubmitted desc, id desc).
CREATE INDEX IF NOT EXISTS idx_reports_date_submitted ON road_reports(date_submitted DESC);
CREATE INDEX IF NOT EXISTS idx_reports_status_date_submitted ON road_reports(status, date_submitted DESC);

-- =========================================================
-- 4. notifications: entity maps recipient_id, baseline created user_id
-- =========================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'notifications' AND column_name = 'user_id')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'notifications' AND column_name = 'recipient_id') THEN
        ALTER TABLE notifications RENAME COLUMN user_id TO recipient_id;
    END IF;
END $$;

ALTER TABLE notifications ALTER COLUMN recipient_id SET NOT NULL;

-- The entity does not map the legacy report_id FK (notifications carry a free-form
-- type/title/message instead), so drop it to satisfy ddl-auto=validate.
ALTER TABLE notifications DROP COLUMN IF EXISTS report_id;

DROP INDEX IF EXISTS idx_notifications_user_id;
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_id ON notifications(recipient_id);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created ON notifications(recipient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_unread ON notifications(recipient_id, is_read);

-- =========================================================
-- 5. activity_logs: migrate old (action/details/ip/created_at/user_id) to entity model
-- =========================================================
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS timestamp TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_id BIGINT;
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_name VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_role VARCHAR(100);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_office VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS category VARCHAR(100);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS target_entity VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS status VARCHAR(50);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS http_method VARCHAR(20);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS user_agent TEXT;

-- Backfill from legacy columns before dropping them.
UPDATE activity_logs SET timestamp   = COALESCE(timestamp, created_at) WHERE timestamp IS NULL;
UPDATE activity_logs SET actor_id    = COALESCE(actor_id, user_id);
UPDATE activity_logs SET description = COALESCE(description, details);
UPDATE activity_logs SET category    = COALESCE(category, 'SYSTEM');
UPDATE activity_logs SET status      = COALESCE(status, 'SUCCESS');

ALTER TABLE activity_logs ALTER COLUMN timestamp SET NOT NULL;
ALTER TABLE activity_logs ALTER COLUMN category SET NOT NULL;
ALTER TABLE activity_logs ALTER COLUMN action SET NOT NULL;

ALTER TABLE activity_logs DROP COLUMN IF EXISTS details;
ALTER TABLE activity_logs DROP COLUMN IF EXISTS created_at;
ALTER TABLE activity_logs DROP COLUMN IF EXISTS user_id;

DROP INDEX IF EXISTS idx_activity_logs_user_id;
CREATE INDEX IF NOT EXISTS idx_activity_logs_timestamp ON activity_logs(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_activity_logs_category ON activity_logs(category);

-- =========================================================
-- 6. system_settings: entity is a single-row settings record with maintenance_mode
-- =========================================================
ALTER TABLE system_settings ADD COLUMN IF NOT EXISTS maintenance_mode BOOLEAN NOT NULL DEFAULT FALSE;

-- setting_key was NOT NULL UNIQUE in baseline but the entity never populates it.
ALTER TABLE system_settings ALTER COLUMN setting_key DROP NOT NULL;

-- Ensure the singleton row exists.
INSERT INTO system_settings (id, maintenance_mode)
VALUES (1, FALSE)
ON CONFLICT (id) DO NOTHING;

-- =========================================================
-- 7. report_status_logs: entity timeline model vs baseline audit model
-- =========================================================
ALTER TABLE report_status_logs ADD COLUMN IF NOT EXISTS action VARCHAR(50);
ALTER TABLE report_status_logs ADD COLUMN IF NOT EXISTS actor_name VARCHAR(100);
ALTER TABLE report_status_logs ADD COLUMN IF NOT EXISTS actor_role VARCHAR(50);
ALTER TABLE report_status_logs ADD COLUMN IF NOT EXISTS attachment_url TEXT;

UPDATE report_status_logs SET action = COALESCE(action, 'STATUS_CHANGE');

ALTER TABLE report_status_logs ALTER COLUMN action SET NOT NULL;

-- Baseline created the audit column as changed_at; the entity (and this
-- migration) use created_at. Rename it on databases that still have the
-- legacy name, then add it if neither column exists (fresh installs differ).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'report_status_logs' AND column_name = 'changed_at')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema()
                         AND table_name = 'report_status_logs' AND column_name = 'created_at') THEN
        ALTER TABLE report_status_logs RENAME COLUMN changed_at TO created_at;
    END IF;
END $$;

ALTER TABLE report_status_logs ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP;

UPDATE report_status_logs SET created_at = COALESCE(created_at, CURRENT_TIMESTAMP) WHERE created_at IS NULL;
ALTER TABLE report_status_logs ALTER COLUMN created_at SET NOT NULL;

-- The entity does not map the legacy changed_by_user_id FK. It is superseded by
-- actor_name / actor_role below, so drop it to satisfy ddl-auto=validate.
ALTER TABLE report_status_logs DROP COLUMN IF EXISTS changed_by_user_id;

-- =========================================================
-- 8. road_reports: remaining entity snapshot / tracking columns
--    (city_road_code, cv_damage_classification, date_submitted handled above)
-- =========================================================
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS damage_length DOUBLE PRECISION;
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS damage_width DOUBLE PRECISION;
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS deferral_reason TEXT;
