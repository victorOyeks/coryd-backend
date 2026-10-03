--liquibase formatted sql

--changeset coryd:008-driver-subaccounts
-- The driver's share is now split to their own Paystack subaccount at charge time instead of
-- accruing here and being transferred out later, so a subaccount code sits beside the transfer
-- recipient code the withdrawal flow used.
ALTER TABLE payment.payout_recipients ADD COLUMN IF NOT EXISTS gateway_subaccount_code VARCHAR(100);

-- Refunds that land after Paystack has already settled the driver come out of our balance, so what
-- the driver owes is tracked here and recovered by taking a larger cut of their next bookings.
ALTER TABLE payment.driver_earnings_accounts
    ADD COLUMN IF NOT EXISTS settlement_debt NUMERIC(12, 2) NOT NULL DEFAULT 0;
