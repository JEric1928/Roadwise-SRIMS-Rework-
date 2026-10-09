-- V7 Additional Performance & Duplicate Detection Indexes

CREATE INDEX IF NOT EXISTS idx_reports_date_submitted ON road_reports(date_submitted);
CREATE INDEX IF NOT EXISTS idx_reports_lat_lng ON road_reports(latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_reports_city_road_name ON road_reports(city_road_name);
CREATE INDEX IF NOT EXISTS idx_reports_date_lat_lng ON road_reports(date_submitted, latitude, longitude);
