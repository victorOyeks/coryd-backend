--liquibase formatted sql

--changeset coryd:006-booking-pickup-point
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS pickup_label VARCHAR(255);
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS pickup_lat DOUBLE PRECISION;
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS pickup_lng DOUBLE PRECISION;
