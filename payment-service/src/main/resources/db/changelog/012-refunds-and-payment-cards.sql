--liquibase formatted sql

--changeset coryd:030-refunds
-- One row per refund back to a card, so payment histories can show each one with its own amount,
-- date and status. user_id is who gets the money back.
CREATE TABLE IF NOT EXISTS payment.refunds
(
    id                     VARCHAR(36) PRIMARY KEY,
    custom_id              VARCHAR(20) UNIQUE,
    user_id                VARCHAR(36)    NOT NULL,
    booking_id             VARCHAR(36),
    payment_transaction_id VARCHAR(36),
    amount                 NUMERIC(10, 2) NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    kind                   VARCHAR(30)    NOT NULL,
    reason                 VARCHAR(255),
    refunded_at            TIMESTAMP,
    created_at             TIMESTAMP      NOT NULL,
    updated_at             TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_refunds_user ON payment.refunds (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_refunds_booking ON payment.refunds (booking_id);

--changeset coryd:031-payment-transaction-card
-- The card a payment was made with, for the rider's payment history.
ALTER TABLE payment.payment_transactions ADD COLUMN IF NOT EXISTS card_type VARCHAR(50);
ALTER TABLE payment.payment_transactions ADD COLUMN IF NOT EXISTS card_last4 VARCHAR(4);
