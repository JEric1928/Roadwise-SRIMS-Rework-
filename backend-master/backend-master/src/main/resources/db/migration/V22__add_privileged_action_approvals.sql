-- V22: Maker-checker approval for privileged actions.
--
-- V21 gave roles a rank, which stopped one administrator from resetting another
-- administrator's password and logging in as them. What it did not answer is the case the
-- business actually has: the two administrators of one office are peers, and one of them
-- legitimately needs to act on the other (handover, a locked-out colleague, a departure).
--
-- Lowering the rank check would restore the seizure path. Instead this migration records a
-- request that a second administrator has to approve: the maker asks, a different
-- administrator holding `user:approve` agrees, and only then may the maker run the action
-- once, redeeming the approval. A single compromised administrative session is therefore
-- no longer enough to reach a peer account.
--
-- Replay-safe: the chain is replayed from V1 on a database with the schema but no
-- flyway_schema_history, so every statement must tolerate re-execution.

CREATE TABLE IF NOT EXISTS privileged_action_request (
    id             BIGSERIAL PRIMARY KEY,
    permission_key VARCHAR(100) NOT NULL REFERENCES authz_permission(permission_key),
    target_user_id BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_by   BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Why the action is needed. Required: an approval nobody can explain is not a control.
    reason         VARCHAR(500) NOT NULL,
    -- PENDING -> APPROVED|DENIED, then APPROVED -> EXECUTED exactly once. The status
    -- transitions are guarded by conditional updates (see PrivilegedActionRequestRepository)
    -- so two approvers racing on the same request can only produce one decision.
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    requested_at   TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by     BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    decided_at     TIMESTAMP WITHOUT TIME ZONE,
    decision_note  VARCHAR(500),
    executed_at    TIMESTAMP WITHOUT TIME ZONE,
    -- A request that nobody answers must stop being usable, otherwise an approval from
    -- months ago is a standing key to somebody's account.
    expires_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_privileged_actions_status
    ON privileged_action_request(status, expires_at);

CREATE INDEX IF NOT EXISTS idx_privileged_actions_requester
    ON privileged_action_request(requested_by, status);

CREATE INDEX IF NOT EXISTS idx_privileged_actions_target
    ON privileged_action_request(target_user_id, status);

INSERT INTO authz_permission (permission_key, description) VALUES
    ('user:approve', 'Approve another administrator''s privileged action request.')
ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description;

INSERT INTO authz_role_permission (role_key, permission_key) VALUES
    ('ADMIN',      'user:approve'),
    ('CPDO_ADMIN', 'user:approve')
ON CONFLICT (role_key, permission_key) DO NOTHING;
