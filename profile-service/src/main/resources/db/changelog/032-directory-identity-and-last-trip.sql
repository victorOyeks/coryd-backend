--liquibase formatted sql

--changeset coryd:094-profile-directory-identity-and-last-trip
-- The people-first Riders and Drivers directories: each rider's identity (NIN) status, and when each rider and driver
-- last completed a trip. Both are kept current like the rest of the index (identity changes rebuild the rider's row;
-- lastTripAt rides on trip-service's stats events) and recomputed by the nightly / startup resyncs.
ALTER TABLE profile.rider_index ADD COLUMN IF NOT EXISTS identity_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE profile.rider_index ADD COLUMN IF NOT EXISTS last_trip_at TIMESTAMP;
ALTER TABLE profile.driver_index ADD COLUMN IF NOT EXISTS last_trip_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_rider_index_identity ON profile.rider_index (identity_status);
CREATE INDEX IF NOT EXISTS idx_rider_index_last_trip ON profile.rider_index (last_trip_at);
CREATE INDEX IF NOT EXISTS idx_driver_index_last_trip ON profile.driver_index (last_trip_at);

--changeset coryd:095-profile-rider-index-identity-backfill
-- Each indexed rider's identity status, worked out as the ops identity screen's tabs are (IdentityReviewService's BASE
-- query), with live Smile ID's try limits (5 for NIN + selfie, 3 for the NIN lookup). In sandbox nobody is locked out;
-- the nightly rider index rebuild puts any such row right.
UPDATE profile.rider_index ri
SET identity_status = t.tab
FROM (
    SELECT iv.user_id,
           CASE
               WHEN iv.status = 'APPROVED' THEN 'APPROVED'
               WHEN iv.status = 'IN_REVIEW' THEN 'IN_REVIEW'
               WHEN iv.status = 'PENDING' AND dp.nin_status = 'APPROVED' THEN 'PENDING'
               WHEN coalesce(s.attempts, 0) >= (CASE WHEN s.check_type = 'NIN_LOOKUP' THEN 3 ELSE 5 END) THEN 'LOCKED_OUT'
               WHEN iv.status = 'REJECTED' OR s.retry_reason IS NOT NULL THEN 'FAILED'
               ELSE 'PENDING'
           END AS tab
    FROM profile.identity_verifications iv
    LEFT JOIN LATERAL (
        SELECT c.attempts, c.check_type, c.retry_reason FROM profile.smile_id_checks c
        WHERE c.user_id = iv.user_id AND c.check_type IN ('NIN', 'NIN_LOOKUP')
        ORDER BY c.updated_at DESC LIMIT 1) s ON TRUE
    LEFT JOIN profile.driver_profiles dp ON dp.user_id = iv.user_id
) t
WHERE t.user_id = ri.rider_id;

--changeset coryd:096-profile-directory-last-trip-backfill splitStatements:false
-- When each indexed rider and driver last completed a trip, read once from trip-service's tables in the same database:
-- the time the booking was marked completed, else the trip's completion, else the booking's last update. Skipped where
-- trip-service's schema isn't there or readable (a fresh database, profile-service's own tests); trip-service's startup
-- stats sync sends the same numbers anyway.
DO $$
BEGIN
    IF to_regclass('trip.bookings') IS NOT NULL AND to_regclass('trip.route_posts') IS NOT NULL
            AND to_regclass('trip.booking_status_history') IS NOT NULL THEN
        WITH done AS (
            SELECT b.rider_id,
                   coalesce((SELECT max(h.created_at) FROM trip.booking_status_history h
                             WHERE h.booking_id = b.id AND h.status = 'COMPLETED'), r.completed_at, b.updated_at) AS completed_at
            FROM trip.bookings b JOIN trip.route_posts r ON r.id = b.route_post_id
            WHERE b.status = 'COMPLETED'
        )
        UPDATE profile.rider_index ri SET last_trip_at = t.last_trip_at
        FROM (SELECT rider_id, max(completed_at) AS last_trip_at FROM done GROUP BY rider_id) t
        WHERE t.rider_id = ri.rider_id;

        WITH done AS (
            SELECT r.driver_id,
                   coalesce((SELECT max(h.created_at) FROM trip.booking_status_history h
                             WHERE h.booking_id = b.id AND h.status = 'COMPLETED'), r.completed_at, b.updated_at) AS completed_at
            FROM trip.bookings b JOIN trip.route_posts r ON r.id = b.route_post_id
            WHERE b.status = 'COMPLETED'
        )
        UPDATE profile.driver_index di SET last_trip_at = t.last_trip_at
        FROM (SELECT driver_id, max(completed_at) AS last_trip_at FROM done GROUP BY driver_id) t
        WHERE t.driver_id = di.driver_id;
    END IF;
EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'last_trip_at backfill skipped (no access to trip schema) - trip-service''s stats sync fills it';
END $$;
