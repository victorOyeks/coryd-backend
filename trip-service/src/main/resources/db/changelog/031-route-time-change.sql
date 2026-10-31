--liquibase formatted sql

--changeset coryd:062-trip-route-time-change
-- A driver running late moves their trip instead of cancelling it. Riders keep their seat and are told,
-- and one whose trip moved far enough from what they booked can cancel free.
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS original_departure_time TIMESTAMP;
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS time_change_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS departure_shift_minutes INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS trip.route_time_changes (
    id                      VARCHAR(36) PRIMARY KEY,
    custom_id               VARCHAR(20) UNIQUE,
    route_post_id           VARCHAR(36) NOT NULL,
    driver_id               VARCHAR(36) NOT NULL,
    previous_departure_time TIMESTAMP   NOT NULL,
    new_departure_time      TIMESTAMP   NOT NULL,
    riders_booked           INTEGER     NOT NULL,
    last_minute             BOOLEAN     NOT NULL,
    created_at              TIMESTAMP   NOT NULL,
    updated_at              TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_route_time_changes_route ON trip.route_time_changes (route_post_id);
CREATE INDEX IF NOT EXISTS idx_route_time_changes_driver_created ON trip.route_time_changes (driver_id, created_at);
