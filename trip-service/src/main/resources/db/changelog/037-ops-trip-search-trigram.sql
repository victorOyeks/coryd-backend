--liquibase formatted sql

--changeset coryd:180-ops-trip-search-trigram runAlways:true splitStatements:false
-- Ops Trips search matches either end of the route anywhere in the label (lower(label) LIKE '%term%') or the start of the
-- trip id. Trigram indexes on both labels plus a pattern index on the id let Postgres answer all three with one BitmapOr
-- instead of reading every trip.
--
-- pg_trgm is installed once per database; on every CoRyd database it lives in the profile schema (profile-service's
-- 070-profile-driver-index creates it there). The operator class is used from whichever schema holds it, so this never
-- tries to install it a second time (CREATE EXTENSION fails when it's in another schema). On a database that doesn't have
-- it yet (trip migrating before profile, or trip on its own) it is installed - into profile when that schema exists, so
-- profile-service's own unqualified indexes still find it.
--
-- Never fails a deploy: if the extension can't be installed or an index can't be built, it's a WARNING and the search
-- simply stays a scan. runAlways so a skipped index is built on a later start; once all exist it's a catalog check.
DO $$
DECLARE
    trgm text;
BEGIN
    IF to_regclass('trip.idx_route_posts_origin_label_trgm') IS NOT NULL
            AND to_regclass('trip.idx_route_posts_destination_label_trgm') IS NOT NULL
            AND to_regclass('trip.idx_route_posts_id_lower_pattern') IS NOT NULL THEN
        RETURN;
    END IF;

    BEGIN
        CREATE INDEX IF NOT EXISTS idx_route_posts_id_lower_pattern ON trip.route_posts (lower(id) text_pattern_ops);
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'Ops trip id prefix index not built (%)', SQLERRM;
    END;

    SELECT n.nspname INTO trgm FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pg_trgm';
    IF trgm IS NULL THEN
        BEGIN
            IF to_regnamespace('profile') IS NOT NULL THEN
                CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA profile;
            ELSE
                CREATE EXTENSION IF NOT EXISTS pg_trgm;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'pg_trgm could not be installed (%) - Ops trip search stays unindexed', SQLERRM;
            RETURN;
        END;
        SELECT n.nspname INTO trgm FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pg_trgm';
    END IF;

    BEGIN
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_route_posts_origin_label_trgm ON trip.route_posts USING gin (lower(origin_label) %I.gin_trgm_ops)', trgm);
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_route_posts_destination_label_trgm ON trip.route_posts USING gin (lower(destination_label) %I.gin_trgm_ops)', trgm);
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'Ops trip search trigram indexes not built (%) - the search stays a scan until a later start builds them', SQLERRM;
    END;
END $$;
