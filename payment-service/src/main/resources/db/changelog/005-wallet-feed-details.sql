--liquibase formatted sql

--changeset coryd:014-earnings-transaction-rider-id
-- The Wallet feed's TRIP rows show who paid for the trip, so the rider is recorded on the
-- earnings row itself (TripCompletedEvent already carries riderId) instead of the feed having
-- to reach into trip-service per row.
ALTER TABLE payment.earnings_transactions ADD COLUMN IF NOT EXISTS rider_id VARCHAR(36);

-- Rows written before this column existed - payment_transactions is this service's own record of
-- who paid for each booking, so no cross-service backfill is needed. A booking with more than one
-- payment_transaction (a retried payment) has the same rider on every attempt, so which row the
-- join picks doesn't matter.
UPDATE payment.earnings_transactions e
SET rider_id = pt.rider_id
FROM payment.payment_transactions pt
WHERE pt.booking_id = e.booking_id AND e.rider_id IS NULL;

--changeset coryd:015-payout-recipient-snapshot
-- Which bank account a withdrawal actually paid out to. Without this, both the Wallet feed and
-- the withdrawal receipt had to fall back to the driver's *current* default account, so switching
-- default accounts silently rewrote the bank details on every past withdrawal. Recipient rows are
-- never updated or deleted once saved, so pinning the id here is a stable snapshot.
ALTER TABLE payment.payouts ADD COLUMN IF NOT EXISTS payout_recipient_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_payouts_payout_recipient_id ON payment.payouts (payout_recipient_id);

-- Existing payouts predate the column; their driver's current default is the same account the
-- receipt screen was already displaying for them, so this preserves today's behaviour exactly
-- while freezing it in place from now on.
UPDATE payment.payouts p
SET payout_recipient_id = r.id
FROM payment.payout_recipients r
WHERE r.driver_id = p.driver_id AND r.is_default = TRUE AND p.payout_recipient_id IS NULL;
