--liquibase formatted sql

--changeset coryd:001-payment-transactions
CREATE TABLE payment.payment_transactions
(
    id                VARCHAR(36) PRIMARY KEY,
    custom_id         VARCHAR(20) UNIQUE,
    booking_id        VARCHAR(36)           NOT NULL,
    rider_id          VARCHAR(36)           NOT NULL,
    amount            NUMERIC(10, 2) NOT NULL,
    gateway_reference VARCHAR(100)   NOT NULL UNIQUE,
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL
);
CREATE INDEX idx_payment_transactions_booking_id ON payment.payment_transactions (booking_id);

--changeset coryd:002-fare-holds
CREATE TABLE payment.fare_holds
(
    id                     VARCHAR(36) PRIMARY KEY,
    custom_id              VARCHAR(20) UNIQUE,
    payment_transaction_id VARCHAR(36)           NOT NULL,
    booking_id             VARCHAR(36)           NOT NULL,
    driver_id              VARCHAR(36)           NOT NULL,
    amount                 NUMERIC(10, 2) NOT NULL,
    released               BOOLEAN        NOT NULL DEFAULT FALSE,
    refunded               BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMP      NOT NULL,
    updated_at             TIMESTAMP      NOT NULL
);
CREATE INDEX idx_fare_holds_booking_id ON payment.fare_holds (booking_id);

--changeset coryd:003-driver-earnings-accounts
CREATE TABLE payment.driver_earnings_accounts
(
    id                  VARCHAR(36) PRIMARY KEY,
    custom_id           VARCHAR(20) UNIQUE,
    driver_id           VARCHAR(36)           NOT NULL UNIQUE,
    available_balance   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    lifetime_gross      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    lifetime_commission NUMERIC(12, 2) NOT NULL DEFAULT 0,
    created_at          TIMESTAMP      NOT NULL,
    updated_at          TIMESTAMP      NOT NULL
);

--changeset coryd:004-earnings-transactions
CREATE TABLE payment.earnings_transactions
(
    id                VARCHAR(36) PRIMARY KEY,
    custom_id         VARCHAR(20) UNIQUE,
    driver_id         VARCHAR(36)           NOT NULL,
    booking_id        VARCHAR(36)           NOT NULL,
    gross_fare        NUMERIC(10, 2) NOT NULL,
    commission_amount NUMERIC(10, 2) NOT NULL,
    net_credit        NUMERIC(10, 2) NOT NULL,
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL
);
CREATE INDEX idx_earnings_transactions_driver_id ON payment.earnings_transactions (driver_id);

--changeset coryd:005-payout-recipients
CREATE TABLE payment.payout_recipients
(
    id                      VARCHAR(36) PRIMARY KEY,
    custom_id               VARCHAR(20) UNIQUE,
    driver_id               VARCHAR(36)        NOT NULL UNIQUE,
    bank_name               VARCHAR(100) NOT NULL,
    account_number          VARCHAR(20) NOT NULL,
    account_name            VARCHAR(100) NOT NULL,
    gateway_recipient_code  VARCHAR(100),
    created_at              TIMESTAMP   NOT NULL,
    updated_at              TIMESTAMP   NOT NULL
);

--changeset coryd:006-payouts
CREATE TABLE payment.payouts
(
    id              VARCHAR(36) PRIMARY KEY,
    custom_id       VARCHAR(20) UNIQUE,
    driver_id       VARCHAR(36)           NOT NULL,
    amount          NUMERIC(10, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    failure_reason  VARCHAR(500),
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);
CREATE INDEX idx_payouts_driver_id ON payment.payouts (driver_id);
