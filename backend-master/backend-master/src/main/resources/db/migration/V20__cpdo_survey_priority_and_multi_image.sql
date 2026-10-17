-- V20: CPDO technical survey, multi-image attachments, and priority ranking.
--
-- Purpose. Splits the report lifecycle along the RBAC boundary the platform is
-- meant to enforce: a barangay inspector captures *what* and *where* (photos +
-- geolocation + defect category), and a CPDO/CEO engineer later records *how
-- bad* (survey measurements) and *what it costs to fix* (repair plan). Until
-- now those two jobs shared one flat table, so a field inspector's form carried
-- engineering fields they had no business filling in.
--
-- Design notes, in the order they matter:
--
-- 1. Additive only. The production tables keep their names (`road_reports`,
--    `city_roads`) and their BIGSERIAL identities. Renaming to
--    `inspection_reports` / `city_roads_records` or converting every key to UUID
--    would invalidate V1-V19, both JPA entities, and the API contract the
--    frontend already speaks. Every statement below is replay-safe
--    (`IF NOT EXISTS`, guarded DO blocks) because
--    spring.flyway.baseline-on-migrate + baseline-version=0 means the whole
--    chain can be replayed against a schema that already exists.
--
-- 2. DOUBLE PRECISION, not NUMERIC(8,2). Every other measurement column in this
--    schema (damage_length, damage_width, length, width, cv_confidence_score)
--    is DOUBLE PRECISION and every matching entity field is `Double`.
--    spring.jpa.hibernate.ddl-auto=validate compares JDBC types, so a NUMERIC
--    column behind a `Double` field fails validation and the application refuses
--    to boot. Rounding belongs in the presentation layer, not the column type.
--
-- 3. report_images is 1-to-Many, but `road_reports.damage_image` stays. It is
--    the legacy single-photo column and the backfill source below. Dropping it
--    in the same release that introduces its replacement removes the rollback
--    path for no benefit; it is superseded, not deleted.

-- ---------------------------------------------------------------------------
-- 1. CPDO technical survey inputs (engineer-only writes)
-- ---------------------------------------------------------------------------
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS damage_depth DOUBLE PRECISION;          -- centimetres
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS surface_type VARCHAR(50);               -- PORTLAND_CEMENT_CONCRETE | ASPHALT_CONCRETE
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS cpdo_repair_plan TEXT;                  -- official scope of work
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS surveyed_by BIGINT
    REFERENCES users(id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- 2. Computed priority ranking (written by PriorityRankingService)
-- ---------------------------------------------------------------------------
-- cv_severity_score is the *aggregated* class (max across a report's images),
-- not a per-image score; per-image scores live on report_images.
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS cv_severity_score INT;
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS tier_score INT DEFAULT 0;
ALTER TABLE road_reports ADD COLUMN IF NOT EXISTS area_score DOUBLE PRECISION DEFAULT 0.0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'chk_road_reports_cv_severity'
           AND conrelid = 'road_reports'::regclass
    ) THEN
        -- NULL is meaningful: a report can be captured before inference has run.
        ALTER TABLE road_reports
            ADD CONSTRAINT chk_road_reports_cv_severity
            CHECK (cv_severity_score IS NULL OR cv_severity_score IN (1, 2, 3));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'chk_road_reports_surface_type'
           AND conrelid = 'road_reports'::regclass
    ) THEN
        ALTER TABLE road_reports
            ADD CONSTRAINT chk_road_reports_surface_type
            CHECK (surface_type IS NULL OR surface_type IN ('PORTLAND_CEMENT_CONCRETE', 'ASPHALT_CONCRETE'));
    END IF;
END $$;

-- Serves the LexSort read path: filter by status, then order by the two sort
-- keys in the same direction the comparator uses.
CREATE INDEX IF NOT EXISTS idx_road_reports_priority
    ON road_reports (status, tier_score DESC, area_score DESC);

-- ---------------------------------------------------------------------------
-- 3. Multi-image attachments (1-to-Many with road_reports)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS report_images (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT NOT NULL REFERENCES road_reports(id) ON DELETE CASCADE,
    image_url TEXT NOT NULL,
    perspective_type VARCHAR(30) NOT NULL DEFAULT 'CLOSE_UP',
    display_order INT NOT NULL DEFAULT 0,            -- CLOSE_UP=0, WIDE_ANGLE=1, PERPENDICULAR=2
    -- Per-image inference audit trail. gate_passed records Stage-1 verdict so a
    -- rejected-but-retained upload can be explained without re-running the model.
    gate_passed BOOLEAN,
    cv_severity_score INT,
    cv_confidence_score DOUBLE PRECISION,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'chk_report_images_perspective'
           AND conrelid = 'report_images'::regclass
    ) THEN
        ALTER TABLE report_images
            ADD CONSTRAINT chk_report_images_perspective
            CHECK (perspective_type IN ('CLOSE_UP', 'WIDE_ANGLE', 'PERPENDICULAR'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'chk_report_images_cv_severity'
           AND conrelid = 'report_images'::regclass
    ) THEN
        ALTER TABLE report_images
            ADD CONSTRAINT chk_report_images_cv_severity
            CHECK (cv_severity_score IS NULL OR cv_severity_score IN (1, 2, 3));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_report_images_report_id ON report_images (report_id);

-- Backfill: promote each legacy single photo to a CLOSE_UP row. Guarded by
-- NOT EXISTS *per report* rather than a unique index, because image_url is TEXT
-- and may hold a full data URL far past the 2712-byte btree limit — a unique
-- index on it would fail outright, and this backfill has to stay replay-safe.
INSERT INTO report_images (report_id, image_url, perspective_type, display_order)
SELECT r.id, r.damage_image, 'CLOSE_UP', 0
  FROM road_reports r
 WHERE r.damage_image IS NOT NULL
   AND btrim(r.damage_image) <> ''
   AND NOT EXISTS (SELECT 1 FROM report_images ri WHERE ri.report_id = r.id);

-- ---------------------------------------------------------------------------
-- 4. Core arterial flag on the road inventory (LexSort's primary qualifier)
-- ---------------------------------------------------------------------------
ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS is_core_arterial BOOLEAN DEFAULT FALSE;

-- `road_importance` is the existing free-text source of truth ('Core' /
-- 'Non-Core'; V6 backfilled it from road_classification). Derive the boolean
-- once, and preserve any value an operator has already set by hand.
UPDATE city_roads
   SET is_core_arterial = (road_importance ILIKE 'core')
 WHERE road_importance IS NOT NULL
   AND is_core_arterial IS DISTINCT FROM (road_importance ILIKE 'core');

-- Road centreline geometry. Optional for the same reason road_reports.geom is
-- (see V13): managed Postgres often cannot CREATE EXTENSION, and the priority
-- engine only needs `is_core_arterial`. Absent PostGIS degrades to a warning,
-- and the column is created only when the extension resolves.
DO $$
DECLARE
    postgis_schema text;
BEGIN
    SELECT n.nspname
      INTO postgis_schema
      FROM pg_extension e
      JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'postgis';

    IF postgis_schema IS NULL THEN
        RAISE WARNING 'V20: postgis is not installed; city_roads.geom was not created. Priority ranking is unaffected.';
        RETURN;
    END IF;

    EXECUTE format(
        'ALTER TABLE city_roads ADD COLUMN IF NOT EXISTS geom %I.geometry(LineString, 4326)',
        postgis_schema);

    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_city_roads_geom_gist ON city_roads USING GIST (geom)';
END $$;

-- ---------------------------------------------------------------------------
-- 5. Adaptive calibration state (discrete RLS, Module 4)
-- ---------------------------------------------------------------------------
-- One row, enforced by the CHECK. Weights start at 1.0 so the first ranking run
-- reproduces the static LexSort exactly; calibration only bends the ordering
-- once real field audits have been logged.
--
-- covariance_json is TEXT rather than JSONB on purpose: Hibernate's schema
-- validator checks JDBC types, and JSONB (Types.OTHER) behind a String field is
-- a validation failure waiting to happen. The payload is a 16-element row-major
-- 4x4 matrix and is only ever read/written by one service.
CREATE TABLE IF NOT EXISTS priority_calibration (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    weight_severity DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    weight_core_arterial DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    weight_area DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    weight_depth DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    covariance_json TEXT,
    audit_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_priority_calibration_singleton CHECK (id = 1)
);

INSERT INTO priority_calibration (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
