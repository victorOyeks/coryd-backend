--liquibase formatted sql

-- PostGIS for along-the-way matching: each route's road as a geography with a spatial index, so
-- "which routes pass within a walk of this rider" is one indexed query. Every changeset here only
-- runs where PostGIS is available; elsewhere it is skipped (and retried on the next start), and
-- trip-service falls back to matching in Java - see GeoCapabilities.

--changeset coryd:049-postgis-extension failOnError:false
--preconditions onFail:CONTINUE onError:CONTINUE
--precondition-sql-check expectedResult:1 select count(*) from pg_available_extensions where name = 'postgis'
-- Needs a superuser on most servers; if the app user can't, an admin runs it once (in schema public, where
-- trip-service's search path looks: currentSchema=trip,public) and the rest follows on the next start.
CREATE EXTENSION IF NOT EXISTS postgis SCHEMA public;

--changeset coryd:050-route-geography
--preconditions onFail:CONTINUE onError:CONTINUE
--precondition-sql-check expectedResult:1 select count(*) from pg_extension where extname = 'postgis'
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS route_geog geography(LineString, 4326);
CREATE INDEX IF NOT EXISTS idx_route_posts_route_geog ON trip.route_posts USING GIST (route_geog);

--changeset coryd:051-route-geography-trigger splitStatements:false
--preconditions onFail:CONTINUE onError:CONTINUE
--precondition-sql-check expectedResult:1 select count(*) from pg_extension where extname = 'postgis'
-- Kept in step with the route's saved Google line (or the straight line between its ends when it
-- has none) on every insert and update, so the application never writes it. A line that can't be
-- read falls back to the straight line rather than failing the save.
CREATE OR REPLACE FUNCTION trip.route_posts_set_geog() RETURNS trigger AS $$
DECLARE
    road geometry;
BEGIN
    BEGIN
        IF NEW.route_polyline IS NOT NULL AND length(NEW.route_polyline) > 0 THEN
            road := ST_SetSRID(ST_LineFromEncodedPolyline(NEW.route_polyline), 4326);
        END IF;
    EXCEPTION WHEN others THEN
        road := NULL;
    END;
    IF road IS NULL OR ST_NPoints(road) < 2 THEN
        road := ST_MakeLine(ST_SetSRID(ST_MakePoint(NEW.origin_lng, NEW.origin_lat), 4326),
                            ST_SetSRID(ST_MakePoint(NEW.destination_lng, NEW.destination_lat), 4326));
    END IF;
    NEW.route_geog := road::geography;
    RETURN NEW;
END
$$ LANGUAGE plpgsql SET search_path = trip, public;

DROP TRIGGER IF EXISTS trg_route_posts_set_geog ON trip.route_posts;
CREATE TRIGGER trg_route_posts_set_geog
    BEFORE INSERT OR UPDATE OF route_polyline, origin_lat, origin_lng, destination_lat, destination_lng
    ON trip.route_posts
    FOR EACH ROW EXECUTE FUNCTION trip.route_posts_set_geog();

--changeset coryd:052-route-geography-backfill
--preconditions onFail:CONTINUE onError:CONTINUE
--precondition-sql-check expectedResult:1 select count(*) from pg_extension where extname = 'postgis'
-- Fires the trigger once for every route posted before PostGIS was on.
UPDATE trip.route_posts SET route_polyline = route_polyline WHERE route_geog IS NULL;
