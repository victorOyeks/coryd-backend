--liquibase formatted sql

--changeset coryd:048-along-the-way
-- Along-the-way matching: each route keeps the road line it was posted with, and a booking can say
-- where the rider gets out when they join part-way.
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS route_polyline TEXT;
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS dropoff_label VARCHAR(255);
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS dropoff_lat DOUBLE PRECISION;
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS dropoff_lng DOUBLE PRECISION;
