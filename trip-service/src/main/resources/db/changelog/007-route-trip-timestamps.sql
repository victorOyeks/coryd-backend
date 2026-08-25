--liquibase formatted sql

--changeset coryd:007-route-trip-timestamps
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS started_at TIMESTAMP;
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;
