-- V13: PostGIS-backed report locations (backward compatible).
--
-- Keeps latitude/longitude as the source of truth and the only JPA-mapped
-- fields. Adds a database-maintained geography point plus a GiST index so
-- radius queries (duplicate detection) run as exact spatial predicates.
--
-- PREREQUISITE: the PostGIS extension. Preferred path on Supabase is
-- Dashboard > Database > Extensions > enable "postgis" once; the
-- CREATE EXTENSION below then becomes a no-op. If the extension has never
-- been enabled, this statement creates it, which requires a role with
-- database CREATE privilege (the standard Supabase postgres role qualifies).
-- If neither holds, this migration fails fast at deploy time instead of
-- silently running without spatial support; the application itself still
-- starts and falls back to the legacy bounding-box duplicate check.

CREATE EXTENSION IF NOT EXISTS postgis;

-- Generated point, recomputed by the database on every insert/update.
-- ST_MakePoint takes (longitude, latitude); rows without coordinates
-- (or pre-geotag legacy rows) yield NULL and are simply never matched
-- by the spatial predicate.
ALTER TABLE road_reports
    ADD COLUMN IF NOT EXISTS geom geography(Point, 4326)
    GENERATED ALWAYS AS (
        CASE
            WHEN latitude IS NULL OR longitude IS NULL THEN NULL::geography
            ELSE ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
        END
    ) STORED;

-- Backfill is automatic: Postgres computes STORED generated values for
-- existing rows when the column is added.

CREATE INDEX IF NOT EXISTS idx_reports_geom_gist
    ON road_reports USING GIST (geom);
