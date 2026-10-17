-- V24: Break-glass access for the last-administrator case.
--
-- Maker-checker (V22) assumes a second administrator exists to say yes. There is one
-- scenario it cannot cover and which does happen: the only remaining administrator is
-- locked out, or the only account able to act is the one that needs acting on. Refusing
-- outright turns a policy into an outage, and the usual workaround -- somebody editing the
-- database by hand -- is worse than a controlled, loud, time-boxed grant.
--
-- So: an administrator may grant THEMSELVES the right to act on a peer, for a short time,
-- with a written reason, with every other administrator notified, and with a row that stays
-- behind for review. The grant unlocks the rank rule only. It never adds a permission the
-- holder did not already have.
--
-- Replay-safe, as the rest of the chain is.

CREATE TABLE IF NOT EXISTS break_glass_grant (
    id          BIGSERIAL PRIMARY KEY,
    granted_to  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason      VARCHAR(500) NOT NULL,
    granted_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    revoked_at  TIMESTAMP WITHOUT TIME ZONE,
    -- Review is deliberately not enforced at activation time: blocking the emergency path on
    -- paperwork is how people learn to work around it. It is surfaced afterwards instead.
    reviewed_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMP WITHOUT TIME ZONE,
    review_note VARCHAR(500)
);

-- Serves the one query that matters on the authorization path: is there a live grant for
-- this account right now?
CREATE INDEX IF NOT EXISTS idx_break_glass_active
    ON break_glass_grant(granted_to, expires_at DESC);

CREATE INDEX IF NOT EXISTS idx_break_glass_expiry
    ON break_glass_grant(expires_at DESC);

INSERT INTO authz_permission (permission_key, description) VALUES
    ('user:break-glass', 'Grant yourself time-boxed access to a peer account, with review.')
ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description;

INSERT INTO authz_role_permission (role_key, permission_key) VALUES
    ('ADMIN',      'user:break-glass'),
    ('CPDO_ADMIN', 'user:break-glass')
ON CONFLICT (role_key, permission_key) DO NOTHING;
