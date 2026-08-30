--liquibase formatted sql

--changeset coryd:018-rating-booking-id-nullable
-- Every new driver/rider profile gets one system-seeded 5-star starting rating with no real
-- booking behind it (see RatingSeedEventListener) - every other rating stays booking-backed.
ALTER TABLE trip.ratings ALTER COLUMN booking_id DROP NOT NULL;
