--liquibase formatted sql

-- The road a driver picks while posting (from /maps/route-options): its line goes in the existing
-- route_polyline, and these few words name it for riders ("Ojota, Maryland, Osborne"). Regular routes keep
-- both so every trip they post follows the same road.

--changeset coryd:057-driver-picked-road
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS via_summary VARCHAR(255);
ALTER TABLE trip.route_templates ADD COLUMN IF NOT EXISTS route_polyline TEXT;
ALTER TABLE trip.route_templates ADD COLUMN IF NOT EXISTS via_summary VARCHAR(255);
