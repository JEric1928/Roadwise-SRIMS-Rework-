-- V5 Composite & Performance Indexes for Reports & Dashboards

CREATE INDEX IF NOT EXISTS idx_reports_barangay_status ON road_reports(barangay_id, status);
CREATE INDEX IF NOT EXISTS idx_reports_status_created ON road_reports(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reports_inventory_year ON road_reports(inventory_year);
CREATE INDEX IF NOT EXISTS idx_reports_user_status ON road_reports(user_id, status);

-- activity_logs.user_id and this index are both dropped by V6, so the same guard as the
-- notifications index in V3 applies: a replay must skip it rather than fail on it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema()
                 AND table_name = 'activity_logs' AND column_name = 'user_id') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_activity_logs_user_id ON activity_logs(user_id)';
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_report_status_logs_report_id ON report_status_logs(report_id);
