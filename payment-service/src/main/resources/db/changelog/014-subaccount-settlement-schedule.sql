--liquibase formatted sql

--changeset coryd:014-subaccount-settlement-schedule
-- The payout schedule as last set on each driver's Paystack subaccount. Null on every existing row,
-- so the startup sync sets them all once - they were created weekly, and payouts are now the next working day.
ALTER TABLE payment.payout_recipients ADD COLUMN IF NOT EXISTS gateway_settlement_schedule VARCHAR(20);
