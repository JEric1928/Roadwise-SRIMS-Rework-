-- V23: Append-only audit, and a record of authorization decisions.
--
-- Two gaps made the existing audit trail weaker than it looked:
--
--   * activity_logs accepted UPDATE and DELETE from whoever the application connects as, so
--     the trail could be rewritten by the same code that writes it (or by anything holding
--     the database password);
--   * only business events were recorded. The interesting question after an incident is not
--     "what was written" but "who tried what, and was it allowed" -- and a refused attempt
--     left no trace at all.
--
-- This migration adds both. It does not partition the table; that is a separate, larger
-- change (partitioning rewrites the physical layout and needs a maintenance window), and it
-- is only worth doing once volume justifies it.

-- ---------------------------------------------------------------
-- Append-only enforcement
-- ---------------------------------------------------------------
-- A trigger rather than REVOKE alone: the application connects as the table owner, and an
-- owner is not stopped by REVOKE. The trigger applies to every role, including the owner.
-- The escape hatch is an explicit session setting, so a deliberate maintenance operation
-- (retention, an approved correction) is possible and leaves its own mark in the logs; it
-- cannot be triggered by an ordinary UPDATE.
CREATE OR REPLACE FUNCTION roadwise_deny_audit_mutation() RETURNS trigger AS $$
BEGIN
    IF coalesce(current_setting('roadwise.audit_maintenance', true), '') = 'on' THEN
        RETURN coalesce(NEW, OLD);
    END IF;
    RAISE EXCEPTION '%.% is append-only: % is not permitted', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = '42501';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS activity_logs_append_only ON activity_logs;
CREATE TRIGGER activity_logs_append_only
    BEFORE UPDATE OR DELETE ON activity_logs
    FOR EACH ROW EXECUTE FUNCTION roadwise_deny_audit_mutation();

-- Belt and braces for any role that is not the owner.
REVOKE UPDATE, DELETE ON activity_logs FROM PUBLIC;

-- ---------------------------------------------------------------
-- Authorization decisions
-- ---------------------------------------------------------------
-- One row per privileged decision: every canActOn() outcome (allow or deny), and every
-- denied permission check. Allowed routine reads are deliberately not recorded -- that is
-- noise, and it would put a write behind every authenticated request.
CREATE TABLE IF NOT EXISTS authz_decision_log (
    id             BIGSERIAL PRIMARY KEY,
    decided_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id       BIGINT,
    permission_key VARCHAR(100) NOT NULL,
    target_user_id BIGINT,
    -- ALLOW | DENY
    decision       VARCHAR(8)   NOT NULL,
    -- Why, in one of the small set of reasons AuthorizationService can produce:
    -- NO_PERMISSION | PEER_OR_SUPERIOR | APPROVED | BREAK_GLASS | RANK | SELF |
    -- UNKNOWN_RANK | UNKNOWN_TARGET | NO_APPROVAL
    reason         VARCHAR(40)  NOT NULL,
    http_method    VARCHAR(10),
    request_path   VARCHAR(255),
    correlation_id VARCHAR(64)  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_authz_decision_time
    ON authz_decision_log(decided_at DESC);

CREATE INDEX IF NOT EXISTS idx_authz_decision_actor
    ON authz_decision_log(actor_id, decided_at DESC);

CREATE INDEX IF NOT EXISTS idx_authz_decision_denied
    ON authz_decision_log(decision, decided_at DESC);

DROP TRIGGER IF EXISTS authz_decision_log_append_only ON authz_decision_log;
CREATE TRIGGER authz_decision_log_append_only
    BEFORE UPDATE OR DELETE ON authz_decision_log
    FOR EACH ROW EXECUTE FUNCTION roadwise_deny_audit_mutation();

REVOKE UPDATE, DELETE ON authz_decision_log FROM PUBLIC;
