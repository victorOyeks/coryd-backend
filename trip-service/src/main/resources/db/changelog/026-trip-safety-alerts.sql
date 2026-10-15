--liquibase formatted sql

--changeset coryd:045-trip-safety-alerts
CREATE TABLE IF NOT EXISTS trip.trip_safety_alerts
(
    id            VARCHAR(36) PRIMARY KEY,
    custom_id     VARCHAR(20) UNIQUE,
    route_post_id VARCHAR(36) NOT NULL,
    kind          VARCHAR(20) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    latitude      DOUBLE PRECISION,
    longitude     DOUBLE PRECISION,
    escalated_at  TIMESTAMP,
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_trip_safety_alerts_open ON trip.trip_safety_alerts (status, created_at);
CREATE INDEX IF NOT EXISTS idx_trip_safety_alerts_route ON trip.trip_safety_alerts (route_post_id);

CREATE TABLE IF NOT EXISTS trip.trip_safety_answers
(
    alert_id VARCHAR(36) NOT NULL REFERENCES trip.trip_safety_alerts (id) ON DELETE CASCADE,
    user_id  VARCHAR(36) NOT NULL,
    answer   VARCHAR(10) NOT NULL,
    PRIMARY KEY (alert_id, user_id)
);
