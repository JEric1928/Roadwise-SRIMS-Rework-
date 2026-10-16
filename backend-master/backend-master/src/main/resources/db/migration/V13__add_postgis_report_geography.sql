-- V13: PostGIS-backed report locations (backward compatible, best effort).
--
-- Keeps latitude/longitude as the source of truth and the only JPA-mapped
-- fields. Adds a database-maintained geography point plus a GiST index so
-- radius queries (duplicate detection) run as exact spatial predicates.
--
-- The spatial column is an optimisation, not a prerequisite for running the
-- application. This migration used to abort the entire deploy when PostGIS was
-- missing, unreadable to the role, or installed into a schema that is not on the
-- search_path (Supabase installs it into `extensions`): a fresh container, a CI
-- database or a restricted managed instance could not migrate at all. It now
-- resolves the extension's schema at run time and degrades with a WARNING, because
-- DuplicateDetectionService already falls back to the bounding-box query when
-- `geom` is absent. Install PostGIS where you want the indexed spatial predicate,
-- not to make the application start.
--
-- Preferred path on Supabase remains an operator step:
-- Dashboard > Database > Extensions > enable "postgis" once.
DO $$
DECLARE
    postgis_schema text;
BEGIN
    -- Requires a role with database CREATE privilege; the standard Supabase postgres
    -- role qualifies. A failure here is reported rather than fatal: the extension may
    -- simply have to be enabled by an operator instead.
    BEGIN
        CREATE EXTENSION IF NOT EXISTS postgis;
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'V13: could not create the postgis extension (%); continuing without spatial support.', SQLERRM;
    END;

    -- The extension may live in `extensions` rather than `public`, so everything below
    -- is qualified with the schema it actually landed in.
    SELECT n.nspname
      INTO postgis_schema
      FROM pg_extension e
      JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'postgis';

    IF postgis_schema IS NULL THEN
        RAISE WARNING 'V13: postgis is not installed; road_reports.geom was not created and duplicate detection stays on the bounding-box fallback.';
        RETURN;
    END IF;

    -- Generated point, recomputed by the database on every insert/update.
    -- ST_MakePoint takes (longitude, latitude); rows without coordinates
    -- (or pre-geotag legacy rows) yield NULL and are simply never matched
    -- by the spatial predicate. Backfill is automatic: Postgres computes
    -- STORED generated values for existing rows when the column is added.
    --
    -- Type and functions are schema-qualified so the stored definition does not
    -- depend on the search_path of whichever session re-runs this migration.
    EXECUTE format(
        'ALTER TABLE road_reports
             ADD COLUMN IF NOT EXISTS geom %1$I.geography(Point, 4326)
             GENERATED ALWAYS AS (
                 CASE
                     WHEN latitude IS NULL OR longitude IS NULL THEN NULL::%1$I.geography
                     ELSE %1$I.ST_SetSRID(%1$I.ST_MakePoint(longitude, latitude), 4326)::%1$I.geography
                 END
             ) STORED',
        postgis_schema);

    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_reports_geom_gist ON road_reports USING GIST (geom)';
END $$;
