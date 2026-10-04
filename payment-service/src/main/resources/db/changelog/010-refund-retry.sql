--liquibase formatted sql

--changeset coryd:028-fare-hold-refund-due
-- A refund Paystack refused or couldn't be reached for - retried by RefundRetryJob until it goes through.
ALTER TABLE payment.fare_holds ADD COLUMN IF NOT EXISTS refund_due NUMERIC(10, 2) NOT NULL DEFAULT 0;
ALTER TABLE payment.fare_holds ADD COLUMN IF NOT EXISTS refund_reason VARCHAR(255);
