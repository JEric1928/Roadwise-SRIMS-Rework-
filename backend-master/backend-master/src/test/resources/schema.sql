-- Test-only policy fixture.
--
-- The authorization reference tables are created by Flyway (V21/V22/V24), and the test
-- profile deliberately disables Flyway because the chain contains Postgres-only steps
-- (PostGIS in V13). Without them, AuthorizationService's SQL fails on H2 and every endpoint
-- annotated with @authz answers 500 -- which is exactly what six controller tests did.
--
-- This file reproduces the reference data only: roles, aliases, permissions, grants, and the
-- decision log's shape. The migrations remain the source of truth; drift shows up as a
-- failing @SpringBootTest rather than as a silent difference. Nothing here is used outside
-- the test profile.
--
-- MERGE ... KEY keeps the script idempotent: contexts share one in-memory database
-- (jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1), so it runs more than once per JVM.

CREATE TABLE IF NOT EXISTS authz_role (
    role_key     VARCHAR(50)  PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    rank_order   INT          NOT NULL,
    description  VARCHAR(1000)
);

CREATE TABLE IF NOT EXISTS authz_role_alias (
    role_text VARCHAR(50) PRIMARY KEY,
    role_key  VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS authz_permission (
    permission_key VARCHAR(100) PRIMARY KEY,
    description    VARCHAR(1000)
);

CREATE TABLE IF NOT EXISTS authz_role_permission (
    role_key       VARCHAR(50)  NOT NULL,
    permission_key VARCHAR(100) NOT NULL,
    PRIMARY KEY (role_key, permission_key)
);

CREATE TABLE IF NOT EXISTS authz_decision_log (
    id             BIGSERIAL PRIMARY KEY,
    decided_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id       BIGINT,
    permission_key VARCHAR(100) NOT NULL,
    target_user_id BIGINT,
    decision       VARCHAR(8)   NOT NULL,
    reason         VARCHAR(40)  NOT NULL,
    http_method    VARCHAR(10),
    request_path   VARCHAR(255),
    correlation_id VARCHAR(64)  NOT NULL
);

MERGE INTO authz_role (role_key, display_name, rank_order, description) KEY (role_key) VALUES
    ('BARANGAY',   'Barangay Official',  10, 'Own barangay only; no administrative capability.'),
    ('ENGINEER',   'City Engineer',      20, 'Reads every barangay and drives field work.'),
    ('CPDO_ADMIN', 'CPDO Administrator', 50, 'Administrative tier.'),
    ('ADMIN',      'Administrator',      50, 'Legacy administrative tier.');

MERGE INTO authz_role_alias (role_text, role_key) KEY (role_text) VALUES
    ('admin',         'ADMIN'),
    ('cpdo admin',    'CPDO_ADMIN'),
    ('cpdo_admin',    'CPDO_ADMIN'),
    ('engineer',      'ENGINEER'),
    ('city engineer', 'ENGINEER'),
    ('ceo',           'ENGINEER'),
    ('barangay',      'BARANGAY');

MERGE INTO authz_permission (permission_key, description) KEY (permission_key) VALUES
    ('user:read',            'List and inspect accounts.'),
    ('user:provision',       'Create barangay official accounts.'),
    ('user:manage',          'Edit another account record or status.'),
    ('user:reset-password',  'Issue a temporary password for another account.'),
    ('user:grant-role',      'Turn over an administrative office.'),
    ('settings:maintenance', 'Toggle city-wide maintenance mode.'),
    ('user:approve',         'Approve a privileged action request.'),
    ('user:break-glass',     'Time-boxed access to a peer account.');

MERGE INTO authz_role_permission (role_key, permission_key)
KEY (role_key, permission_key)
SELECT r.role_key, p.permission_key
  FROM authz_role r
 CROSS JOIN authz_permission p
 WHERE r.role_key IN ('ADMIN', 'CPDO_ADMIN');
