-- V1 Baseline Schema for RoadWise Application

CREATE TABLE IF NOT EXISTS barangays (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    first_name VARCHAR(255),
    middle_name VARCHAR(255),
    last_name VARCHAR(255),
    email VARCHAR(255),
    phone_number VARCHAR(50),
    birthday DATE,
    gender VARCHAR(50),
    profile_picture TEXT,
    status VARCHAR(50),
    failed_login_attempts INT DEFAULT 0,
    lockout_until TIMESTAMP,
    barangay_id BIGINT REFERENCES barangays(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS city_roads (
    id BIGSERIAL PRIMARY KEY,
    road_name VARCHAR(255) NOT NULL,
    pavement_type VARCHAR(100),
    road_classification VARCHAR(100),
    traffic_level VARCHAR(50),
    length_km DOUBLE PRECISION,
    width_m DOUBLE PRECISION,
    lanes INT,
    barangay_id BIGINT REFERENCES barangays(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS road_reports (
    id BIGSERIAL PRIMARY KEY,
    city_road_name VARCHAR(255),
    reported_by VARCHAR(255),
    damage_description TEXT,
    damage_type VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'Pending Validation',
    severity VARCHAR(50),
    cv_confidence_score DOUBLE PRECISION,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    damage_image TEXT,
    proof_of_repair_image TEXT,
    admin_remarks TEXT,
    repair_remarks TEXT,
    rejection_reason TEXT,
    deferral_reason TEXT,
    extension_reason TEXT,
    extension_status VARCHAR(50),
    extension_target_date DATE,
    last_reminder_sent TIMESTAMP WITHOUT TIME ZONE,
    date_archived TIMESTAMP WITHOUT TIME ZONE,
    target_completion_date DATE,
    actual_completion_date DATE,
    length DOUBLE PRECISION,
    width DOUBLE PRECISION,
    damage_length DOUBLE PRECISION,
    damage_width DOUBLE PRECISION,
    length_of_culverts DOUBLE PRECISION,
    number_of_bridges INT,
    inventory_year INT,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    barangay_id BIGINT REFERENCES barangays(id) ON DELETE SET NULL,
    city_road_id BIGINT REFERENCES city_roads(id) ON DELETE SET NULL,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS report_status_logs (
    id BIGSERIAL PRIMARY KEY,
    previous_status VARCHAR(50),
    new_status VARCHAR(50) NOT NULL,
    remarks TEXT,
    changed_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    report_id BIGINT REFERENCES road_reports(id) ON DELETE CASCADE,
    changed_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(50),
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    report_id BIGINT REFERENCES road_reports(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS activity_logs (
    id BIGSERIAL PRIMARY KEY,
    action VARCHAR(255) NOT NULL,
    details TEXT,
    ip_address VARCHAR(100),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS system_settings (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(255) NOT NULL UNIQUE,
    setting_value TEXT
);
