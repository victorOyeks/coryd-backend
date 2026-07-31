--liquibase formatted sql

--changeset coryd:007-payment-transactions-method-purpose
ALTER TABLE payment.payment_transactions
    ADD COLUMN payment_method VARCHAR(20) NOT NULL DEFAULT 'PAYSTACK';
ALTER TABLE payment.payment_transactions
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'BOOKING_PAYMENT';
-- Wallet top-ups have no booking to attach to.
ALTER TABLE payment.payment_transactions
    ALTER COLUMN booking_id DROP NOT NULL;

--changeset coryd:008-wallets
CREATE TABLE payment.wallets
(
    id                VARCHAR(36) PRIMARY KEY,
    custom_id         VARCHAR(20) UNIQUE,
    user_id           VARCHAR(36)    NOT NULL UNIQUE,
    available_balance NUMERIC(12, 2) NOT NULL DEFAULT 0,
    currency          VARCHAR(3)     NOT NULL DEFAULT 'NGN',
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL
);

--changeset coryd:009-wallet-transactions
CREATE TABLE payment.wallet_transactions
(
    id             VARCHAR(36) PRIMARY KEY,
    custom_id      VARCHAR(20) UNIQUE,
    wallet_id      VARCHAR(36)    NOT NULL,
    entry_type     VARCHAR(10)    NOT NULL,
    purpose        VARCHAR(20)    NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    balance_after  NUMERIC(12, 2) NOT NULL,
    reference      VARCHAR(100),
    booking_id     VARCHAR(36),
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL
);
CREATE INDEX idx_wallet_transactions_wallet_id ON payment.wallet_transactions (wallet_id);
