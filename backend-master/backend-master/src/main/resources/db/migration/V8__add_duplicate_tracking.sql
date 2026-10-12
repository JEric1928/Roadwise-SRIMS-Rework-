-- V8: Persist duplicate-detection outcomes on road_reports.
-- The application previously only sent an admin notification when a candidate
-- duplicate was found; nothing was stored on the report itself.

-- potential_duplicate: true when the submission was flagged as a likely duplicate.
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS potential_duplicate BOOLEAN NOT NULL DEFAULT FALSE;

-- duplicate_of_report_id: self-referencing link to the earlier report.
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS duplicate_of_report_id BIGINT REFERENCES road_reports(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_reports_potential_duplicate ON road_reports(potential_duplicate);
CREATE INDEX IF NOT EXISTS idx_reports_duplicate_of ON road_reports(duplicate_of_report_id);
