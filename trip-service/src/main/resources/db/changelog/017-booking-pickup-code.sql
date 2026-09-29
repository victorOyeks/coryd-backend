--liquibase formatted sql

--changeset coryd:029-booking-pickup-code
-- The rider's proof of boarding - the driver can only mark a passenger picked up by entering it.
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS pickup_code VARCHAR(4);
ALTER TABLE trip.bookings ADD COLUMN IF NOT EXISTS pickup_code_attempts INTEGER NOT NULL DEFAULT 0;

-- Bookings already confirmed and still waiting for pickup need a code to be boardable at all.
UPDATE trip.bookings
SET pickup_code = LPAD(FLOOR(RANDOM() * 10000)::INT::TEXT, 4, '0')
WHERE pickup_code IS NULL
  AND status IN ('CONFIRMED', 'EN_ROUTE_TO_PICKUP', 'AT_PICKUP');
