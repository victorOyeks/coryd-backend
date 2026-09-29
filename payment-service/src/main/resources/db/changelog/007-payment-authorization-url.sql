--liquibase formatted sql

--changeset coryd:023-payment-transaction-authorization-url
-- Kept so a retried Pay Now hands back the checkout already opened instead of starting a second
-- Paystack transaction whose reference would overwrite the first - a rider who then paid the first
-- checkout was charged against a reference nothing recognised.
ALTER TABLE payment.payment_transactions ADD COLUMN IF NOT EXISTS authorization_url VARCHAR(512);
