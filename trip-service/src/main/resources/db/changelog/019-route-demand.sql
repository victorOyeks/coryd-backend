--liquibase formatted sql

--changeset coryd:031-notify-me-repeat
-- A "Notify me" can now repeat (e.g. weekdays around 7 AM for 4 weeks). A repeating one has no single
-- desired time: days_mask (Mon=1 ... Sun=64) and time_minutes (minutes after midnight, Lagos time)
-- instead, and expires_at is the end of its last day.
ALTER TABLE trip.notify_me_requests ALTER COLUMN desired_departure_time DROP NOT NULL;
ALTER TABLE trip.notify_me_requests ADD COLUMN IF NOT EXISTS origin_label VARCHAR(255);
ALTER TABLE trip.notify_me_requests ADD COLUMN IF NOT EXISTS destination_label VARCHAR(255);
ALTER TABLE trip.notify_me_requests ADD COLUMN IF NOT EXISTS days_mask INTEGER;
ALTER TABLE trip.notify_me_requests ADD COLUMN IF NOT EXISTS time_minutes INTEGER;
ALTER TABLE trip.notify_me_requests ADD COLUMN IF NOT EXISTS cancelled BOOLEAN NOT NULL DEFAULT FALSE;

--changeset coryd:032-notify-me-notifications
-- Which rides each request has already been told about, so each ride is announced once - and
-- seat_opened_at, so a seat freeing up on a ride that had filled is announced once too.
CREATE TABLE IF NOT EXISTS trip.notify_me_notifications
(
    id             VARCHAR(36) PRIMARY KEY,
    request_id     VARCHAR(36) NOT NULL,
    route_post_id  VARCHAR(36) NOT NULL,
    seat_opened_at TIMESTAMP,
    created_at     TIMESTAMP   NOT NULL,
    CONSTRAINT uq_notify_me_notifications UNIQUE (request_id, route_post_id)
);

--changeset coryd:033-route-searches
-- Every rider search, for rider-demand totals shown to drivers. Never shown per rider, and kept 90 days.
-- *_cell groups nearby points into ~3 km squares; local_minutes (the time asked for, minutes after
-- midnight) and weekend are in Lagos time.
CREATE TABLE IF NOT EXISTS trip.route_searches
(
    id                VARCHAR(36) PRIMARY KEY,
    custom_id         VARCHAR(20) UNIQUE,
    rider_id          VARCHAR(36)      NOT NULL,
    origin_lat        DOUBLE PRECISION NOT NULL,
    origin_lng        DOUBLE PRECISION NOT NULL,
    destination_lat   DOUBLE PRECISION NOT NULL,
    destination_lng   DOUBLE PRECISION NOT NULL,
    origin_label      VARCHAR(255),
    destination_label VARCHAR(255),
    origin_cell       VARCHAR(30)      NOT NULL,
    destination_cell  VARCHAR(30)      NOT NULL,
    local_minutes     INTEGER          NOT NULL,
    weekend           BOOLEAN          NOT NULL,
    result_count      INTEGER          NOT NULL,
    created_at        TIMESTAMP        NOT NULL,
    updated_at        TIMESTAMP        NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_route_searches_created ON trip.route_searches (created_at);

--changeset coryd:034-driver-driving-preferences
-- "Where do you usually drive?" - stands in for trip history until a new driver has posted some routes.
CREATE TABLE IF NOT EXISTS trip.driver_driving_preferences
(
    id                VARCHAR(36) PRIMARY KEY,
    custom_id         VARCHAR(20) UNIQUE,
    driver_id         VARCHAR(36)      NOT NULL UNIQUE,
    start_lat         DOUBLE PRECISION,
    start_lng         DOUBLE PRECISION,
    start_label       VARCHAR(255),
    end_lat           DOUBLE PRECISION,
    end_lng           DOUBLE PRECISION,
    end_label         VARCHAR(255),
    mornings          BOOLEAN          NOT NULL DEFAULT FALSE,
    evenings          BOOLEAN          NOT NULL DEFAULT FALSE,
    demand_alerts     BOOLEAN          NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP        NOT NULL,
    updated_at        TIMESTAMP        NOT NULL
);

--changeset coryd:035-demand-alert-log
-- One row per driver per day they were sent a demand push - the unique key is also what stops two
-- instances of the evening job both sending.
CREATE TABLE IF NOT EXISTS trip.demand_alert_log
(
    id          VARCHAR(36) PRIMARY KEY,
    custom_id   VARCHAR(20) UNIQUE,
    driver_id   VARCHAR(36) NOT NULL,
    sent_on     DATE        NOT NULL,
    route_count INTEGER     NOT NULL,
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uq_demand_alert_log UNIQUE (driver_id, sent_on)
);
