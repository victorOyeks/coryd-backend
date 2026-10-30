--liquibase formatted sql

--changeset coryd:013-subaccount-split-percentage
-- The share we take, as last set on each driver's Paystack subaccount. Null on every existing row,
-- so the startup sync sets them all once - they were created with the driver's share (95) where
-- Paystack expects ours (5), and drivers were paid 5%.
ALTER TABLE payment.payout_recipients ADD COLUMN IF NOT EXISTS gateway_split_percentage NUMERIC(5, 2);
