--liquibase formatted sql

--changeset coryd:044-regular-routes
-- Drivers' regular routes: route_templates existed unused; it now carries the schedule and the trip
-- details, and each posted trip points back at the template it came from.
ALTER TABLE trip.route_templates ADD COLUMN IF NOT EXISTS vehicle_id VARCHAR(36);
ALTER TABLE trip.route_templates ADD COLUMN IF NOT EXISTS days_mask INTEGER;
ALTER TABLE trip.route_templates ADD COLUMN IF NOT EXISTS departure_minutes INTEGER;
ALTER TABLE trip.route_templates ADD COLUMN IF NOT EXISTS fare_per_seat NUMERIC(10, 2);
ALTER TABLE trip.route_templates ADD COLUMN IF NOT EXISTS route_note VARCHAR(255);
ALTER TABLE trip.route_templates ADD COLUMN IF NOT EXISTS active_until DATE;
ALTER TABLE trip.route_templates ADD COLUMN IF NOT EXISTS paused BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_route_templates_driver ON trip.route_templates (driver_id);

CREATE TABLE IF NOT EXISTS trip.route_template_seats
(
    route_template_id VARCHAR(36) NOT NULL REFERENCES trip.route_templates (id) ON DELETE CASCADE,
    rider_id          VARCHAR(36) NOT NULL,
    seat_count        INTEGER     NOT NULL
);

ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS route_template_id VARCHAR(36);
CREATE UNIQUE INDEX IF NOT EXISTS uq_route_posts_template_departure
    ON trip.route_posts (route_template_id, departure_time) WHERE route_template_id IS NOT NULL;
