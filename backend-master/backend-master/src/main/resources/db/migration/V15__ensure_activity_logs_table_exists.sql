-- V15: Guarantee activity_logs table creation for target databases where V14 was marked executed prior to DDL inclusion.

CREATE TABLE IF NOT EXISTS activity_logs (
    id BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id BIGINT,
    actor_name VARCHAR(255),
    actor_role VARCHAR(100),
    actor_office VARCHAR(255),
    category VARCHAR(100) NOT NULL,
    action VARCHAR(255) NOT NULL,
    target_entity VARCHAR(255),
    description TEXT,
    status VARCHAR(50),
    ip_address VARCHAR(100),
    http_method VARCHAR(20),
    user_agent TEXT
);

ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS timestamp TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_id BIGINT;
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_name VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_role VARCHAR(100);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS actor_office VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS category VARCHAR(100);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS action VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS target_entity VARCHAR(255);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS status VARCHAR(50);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS ip_address VARCHAR(100);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS http_method VARCHAR(20);
ALTER TABLE activity_logs ADD COLUMN IF NOT EXISTS user_agent TEXT;

CREATE INDEX IF NOT EXISTS idx_activity_logs_timestamp ON activity_logs(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_activity_logs_category ON activity_logs(category);
