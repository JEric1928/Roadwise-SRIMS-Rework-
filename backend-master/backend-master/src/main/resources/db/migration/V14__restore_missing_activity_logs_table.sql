-- Restore the audit table if an existing database was baselined without it.
-- Fresh installs already create this table in V1 and align it in V6.
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

CREATE INDEX IF NOT EXISTS idx_activity_logs_timestamp ON activity_logs(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_activity_logs_category ON activity_logs(category);
