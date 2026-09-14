--liquibase formatted sql

--changeset coryd:022-payment-fare-hold-booking-id-unique
-- At-most-one PaymentTransaction/FareHold per booking, enforced at the DB level - the retried-payment
-- gap the comment in 005-wallet-feed-details.sql already flagged as merely tolerated ("a booking with
-- more than one payment_transaction... doesn't matter") turned out to matter in production: nothing
-- stopped POST /payments/initiate from inserting a second row per booking, which broke every
-- single-result read (PaymentService.getByBooking(), EarningsService.onTripCompleted()/refund()) with
-- a NonUniqueResultException once a booking actually ended up with two rows. payViaPaystack()/
-- payFromWallet() now upsert keyed on this constraint instead of blindly inserting.

-- Existing duplicates predate the constraint - keep only the most recently created row per booking
-- (the one the application already treats as authoritative) so the index below can be created.
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

-- Partial - booking_id is nullable on payment_transactions (wallet top-ups have none); fare_holds'
-- booking_id is never null, so a plain unique index is enough there.
CREATE UNIQUE INDEX payment_transactions_booking_id_unique ON payment.payment_transactions (booking_id) WHERE booking_id IS NOT NULL;
CREATE UNIQUE INDEX fare_holds_booking_id_unique ON payment.fare_holds (booking_id);
