-- V21: Authorization policy as data.
--
-- Until now authorization was decided by comparing the free-text users.role string with
-- literals scattered across @PreAuthorize and controller bodies. Two problems with that:
--
--   * the role text has already drifted ('ENGINEER' vs 'City Engineer' vs 'CEO' in
--     dashboards), so a rule that matches one spelling silently does not match another;
--   * there is no notion of seniority. Every `hasAnyRole('ADMIN', 'CPDO Admin')` check is
--     flat, which is why any administrator can reset another administrator's password and
--     log in as them (UserController#emergencyPasswordReset).
--
-- This migration introduces the policy model only. It grants nothing new: the permissions
-- below reproduce exactly the role checks they replace, and the alias table joins the
-- existing free-text role to a role key so no user data has to change.
--
-- Written to be replay-safe. This chain is replayed from V1 when a database already has
-- the schema but no flyway_schema_history (spring.flyway.baseline-version=0), so every
-- statement here must tolerate re-execution.

CREATE TABLE IF NOT EXISTS authz_role (
    role_key     VARCHAR(50)  PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    -- Seniority. An administrator may only act on an account of strictly lower rank, so
    -- two peers at the same rank can never reset or disable each other. This is the rule
    -- that a "master admin" role was meant to provide, expressed as data instead.
    rank         INT          NOT NULL,
    description  TEXT
);

-- users.role is free text and stays that way for now. This is the join between it and a
-- role key. Keys are stored lower case because the lookup folds users.role the same way.
CREATE TABLE IF NOT EXISTS authz_role_alias (
    role_text VARCHAR(50) PRIMARY KEY,
    role_key  VARCHAR(50) NOT NULL REFERENCES authz_role(role_key) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS authz_permission (
    permission_key VARCHAR(100) PRIMARY KEY,
    description    TEXT
);

CREATE TABLE IF NOT EXISTS authz_role_permission (
    role_key       VARCHAR(50)  NOT NULL REFERENCES authz_role(role_key) ON DELETE CASCADE,
    permission_key VARCHAR(100) NOT NULL REFERENCES authz_permission(permission_key) ON DELETE CASCADE,
    PRIMARY KEY (role_key, permission_key)
);

-- Ranks are ordinal, not additive: 50 > 20 > 10 is the whole rule. ADMIN and CPDO_ADMIN
-- share a rank on purpose -- they are interchangeable in every existing rule, and making
-- one senior to the other would hand it exactly the peer-takeover power this change
-- removes.
INSERT INTO authz_role (role_key, display_name, rank, description) VALUES
    ('BARANGAY',   'Barangay Official',   10, 'Own barangay only; no administrative capability.'),
    ('ENGINEER',   'City Engineer',       20, 'Reads every barangay and drives field work.'),
    ('CPDO_ADMIN', 'CPDO Administrator',  50, 'Administrative tier. Peers cannot act on each other.'),
    ('ADMIN',      'Administrator',       50, 'Legacy administrative tier, same rank as CPDO_ADMIN.')
ON CONFLICT (role_key) DO UPDATE
    SET display_name = EXCLUDED.display_name,
        rank         = EXCLUDED.rank,
        description  = EXCLUDED.description;

INSERT INTO authz_role_alias (role_text, role_key) VALUES
    ('admin',         'ADMIN'),
    ('cpdo admin',    'CPDO_ADMIN'),
    ('cpdo_admin',    'CPDO_ADMIN'),
    ('engineer',      'ENGINEER'),
    ('city engineer', 'ENGINEER'),
    ('ceo',           'ENGINEER'),
    ('barangay',      'BARANGAY')
ON CONFLICT (role_text) DO UPDATE SET role_key = EXCLUDED.role_key;

INSERT INTO authz_permission (permission_key, description) VALUES
    ('user:read',           'List and inspect accounts.'),
    ('user:provision',      'Create barangay official accounts.'),
    ('user:manage',         'Edit another account''s record or status.'),
    ('user:reset-password', 'Issue a temporary password for another account.'),
    ('user:grant-role',     'Turn over an administrative office, creating its successor.'),
    ('settings:maintenance','Toggle city-wide maintenance mode.')
ON CONFLICT (permission_key) DO UPDATE SET description = EXCLUDED.description;

-- Explicit pairs rather than a cross join: a permission added later for another role must
-- not silently become an administrative one.
INSERT INTO authz_role_permission (role_key, permission_key) VALUES
    ('ADMIN',      'user:read'),
    ('ADMIN',      'user:provision'),
    ('ADMIN',      'user:manage'),
    ('ADMIN',      'user:reset-password'),
    ('ADMIN',      'user:grant-role'),
    ('ADMIN',      'settings:maintenance'),
    ('CPDO_ADMIN', 'user:read'),
    ('CPDO_ADMIN', 'user:provision'),
    ('CPDO_ADMIN', 'user:manage'),
    ('CPDO_ADMIN', 'user:reset-password'),
    ('CPDO_ADMIN', 'user:grant-role'),
    ('CPDO_ADMIN', 'settings:maintenance')
ON CONFLICT (role_key, permission_key) DO NOTHING;
