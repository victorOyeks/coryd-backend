--liquibase formatted sql

--changeset coryd:063-trip-notify-me-any-time-of-day
-- "Tell me when a car leaves from here to Ajah on Sundays" - at any time that day, as a service can run late.
ALTER TABLE trip.notify_me_requests ADD COLUMN IF NOT EXISTS any_time_of_day BOOLEAN NOT NULL DEFAULT FALSE;

--changeset coryd:064-trip-saved-place-home
-- One Home per user: the one-tap destination for "I'm leaving now" and for riders.
CREATE UNIQUE INDEX IF NOT EXISTS uq_saved_places_home ON trip.saved_places (user_id) WHERE type = 'HOME';

--changeset coryd:065-trip-route-search-desired-time
-- When each searching rider wanted to leave - "3 others near you are also going to Ajah" only counts riders leaving about now.
ALTER TABLE trip.route_searches ADD COLUMN IF NOT EXISTS desired_departure_time TIMESTAMP;

--changeset coryd:066-trip-driver-presence
-- Where a driver last opened the app, for "4 people near you are going your way" - one row per driver,
-- overwritten and cleared after 30 minutes, never a trail.
CREATE TABLE IF NOT EXISTS trip.driver_presence (
    driver_id            VARCHAR(36) PRIMARY KEY,
    lat                  DOUBLE PRECISION,
    lng                  DOUBLE PRECISION,
    seen_at              TIMESTAMP,
    last_nearby_alert_at TIMESTAMP,
    alert_day            DATE,
    alerts_that_day      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_driver_presence_seen_at ON trip.driver_presence (seen_at);
