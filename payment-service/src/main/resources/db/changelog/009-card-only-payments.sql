--liquibase formatted sql

--changeset coryd:024-remove-wallet
-- Card-only payments: every fare is split at charge time, so nothing is held for riders or drivers.
-- Pre-launch data only - wallet top-ups and wallet-paid bookings have no card charge to refund against.
DELETE FROM payment.fare_holds
WHERE payment_transaction_id IN (SELECT id FROM payment.payment_transactions WHERE payment_method = 'WALLET');
DELETE FROM payment.payment_transactions WHERE payment_method = 'WALLET' OR purpose <> 'BOOKING_PAYMENT';
DROP TABLE IF EXISTS payment.wallet_transactions;
DROP TABLE IF EXISTS payment.wallets;
DROP TABLE IF EXISTS payment.payouts;
ALTER TABLE payment.driver_earnings_accounts DROP COLUMN IF EXISTS available_balance;

--changeset coryd:025-fare-hold-debt-recovery
-- How much of this fare was routed to us to pay down the driver's settlement debt.
ALTER TABLE payment.fare_holds ADD COLUMN IF NOT EXISTS debt_recovery NUMERIC(10, 2) NOT NULL DEFAULT 0;

--changeset coryd:026-saved-cards
-- A rider's reusable Paystack card authorization. fingerprint is Paystack's card signature, so the
-- same card paid with twice is one row.
CREATE TABLE IF NOT EXISTS payment.saved_cards
(
    id                 VARCHAR(36) PRIMARY KEY,
    custom_id          VARCHAR(20) UNIQUE,
    rider_id           VARCHAR(36)  NOT NULL,
    fingerprint        VARCHAR(100) NOT NULL,
    authorization_code VARCHAR(100) NOT NULL,
    email              VARCHAR(255) NOT NULL,
    card_type          VARCHAR(50),
    last4              VARCHAR(4),
    exp_month          VARCHAR(2),
    exp_year           VARCHAR(4),
    bank               VARCHAR(100),
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    CONSTRAINT uq_saved_cards_rider_fingerprint UNIQUE (rider_id, fingerprint)
);

--changeset coryd:027-driver-settlements
-- Paystack's settlements to each driver's bank, mirrored for the earnings screen.
ALTER TABLE payment.payout_recipients ADD COLUMN IF NOT EXISTS gateway_subaccount_id VARCHAR(50);
CREATE TABLE IF NOT EXISTS payment.driver_settlements
(
    id                     VARCHAR(36) PRIMARY KEY,
    custom_id              VARCHAR(20) UNIQUE,
    driver_id              VARCHAR(36)    NOT NULL,
    gateway_settlement_id  VARCHAR(50)    NOT NULL UNIQUE,
    amount                 NUMERIC(12, 2) NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    settlement_date        TIMESTAMP,
    created_at             TIMESTAMP      NOT NULL,
    updated_at             TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_driver_settlements_driver ON payment.driver_settlements (driver_id, settlement_date);
