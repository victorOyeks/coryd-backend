--liquibase formatted sql

--changeset coryd:005-route-note
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS route_note VARCHAR(255);
