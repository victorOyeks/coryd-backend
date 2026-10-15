--liquibase formatted sql

--changeset coryd:037-sos-route-and-role
-- A driver can raise an SOS on their route with no booking; every incident records its route and
-- which side pressed it, so the Ops Console can list them without joining through bookings.
ALTER TABLE trip.sos_incidents ALTER COLUMN booking_id DROP NOT NULL;
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS route_post_id VARCHAR(36);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS triggered_by_role VARCHAR(20);
UPDATE trip.sos_incidents s SET route_post_id = b.route_post_id,
       triggered_by_role = CASE WHEN s.triggered_by = b.rider_id THEN 'RIDER' ELSE 'DRIVER' END
FROM trip.bookings b WHERE b.id = s.booking_id AND s.route_post_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_sos_incidents_created ON trip.sos_incidents (created_at DESC);
