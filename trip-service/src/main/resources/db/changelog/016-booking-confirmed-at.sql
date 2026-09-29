--liquibase formatted sql

--changeset coryd:028-booking-confirmed-at
-- Set once payment confirms a booking. Separates a paid booking that was later cancelled from a hold
-- that expired or failed payment - both end as CANCELLED_RIDER, but only the first ever happened.
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMP;

UPDATE trip.bookings b
SET confirmed_at = h.first_confirmed
FROM (SELECT booking_id, MIN(created_at) AS first_confirmed
      FROM trip.booking_status_history
      WHERE status = 'CONFIRMED'
      GROUP BY booking_id) h
WHERE b.id = h.booking_id
  AND b.confirmed_at IS NULL;
