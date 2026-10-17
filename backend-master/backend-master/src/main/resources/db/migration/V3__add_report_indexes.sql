-- V3 Report & Performance Indexes

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_barangay_id ON users(barangay_id);

CREATE INDEX IF NOT EXISTS idx_road_reports_status ON road_reports(status);
CREATE INDEX IF NOT EXISTS idx_road_reports_barangay_id ON road_reports(barangay_id);
CREATE INDEX IF NOT EXISTS idx_road_reports_severity ON road_reports(severity);
CREATE INDEX IF NOT EXISTS idx_road_reports_created_at ON road_reports(created_at);
CREATE INDEX IF NOT EXISTS idx_road_reports_city_road_id ON road_reports(city_road_id);

-- notifications.user_id is renamed to recipient_id by V6, which also drops this index.
-- Guarded so a replay skips it instead of failing on a column that no longer exists:
-- Flyway re-runs the whole chain when a history row is missing (out-of-order), which is
-- exactly what turns a repaired checksum into a boot failure.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'notifications' AND column_name = 'user_id') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications(user_id)';
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_notifications_is_read ON notifications(is_read);

CREATE INDEX IF NOT EXISTS idx_mfa_challenges_user_id ON mfa_challenges(user_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_id ON password_reset_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
