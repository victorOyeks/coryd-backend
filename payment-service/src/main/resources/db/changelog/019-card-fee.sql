--liquibase formatted sql

--changeset coryd:186-payment-card-fee
-- M10: the rider (or a driver repaying) covers the card fee, shown before paying. amount stays what was owed (the fare or
-- the debt); card_fee is the fee on top and amount_charged what the card was charged. Older payments keep card_fee 0 and
-- amount_charged null - the app then shows the amount alone, as before.
ALTER TABLE payment.payment_transactions ADD COLUMN IF NOT EXISTS card_fee NUMERIC(10, 2) NOT NULL DEFAULT 0;
ALTER TABLE payment.payment_transactions ADD COLUMN IF NOT EXISTS amount_charged NUMERIC(10, 2);
