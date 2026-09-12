--liquibase formatted sql

--changeset coryd:022-payment-fare-hold-booking-id-unique
-- At-most-one PaymentTransaction/FareHold per booking, enforced at the DB level.

-- Existing duplicates predate the constraint - keep only the most recent row per booking.
UPDATE payment.payment_transactions t
SET booking_id = NULL
WHERE booking_id IS NOT NULL
  AND id NOT IN (
      SELECT DISTINCT ON (booking_id) id
      FROM payment.payment_transactions
      WHERE booking_id IS NOT NULL
      ORDER BY booking_id, created_at DESC
  );

DELETE FROM payment.fare_holds
WHERE id NOT IN (
    SELECT DISTINCT ON (booking_id) id
    FROM payment.fare_holds
    ORDER BY booking_id, created_at DESC
);

-- Partial - booking_id is nullable on payment_transactions (wallet top-ups have none).
CREATE UNIQUE INDEX payment_transactions_booking_id_unique ON payment.payment_transactions (booking_id) WHERE booking_id IS NOT NULL;
CREATE UNIQUE INDEX fare_holds_booking_id_unique ON payment.fare_holds (booking_id);
