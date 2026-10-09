--liquibase formatted sql

--changeset coryd:029-driver-debt-entries
-- Every change to what a driver owes from refunded trips, newest balance on each row - the driver's history.
CREATE TABLE IF NOT EXISTS payment.driver_debt_entries
(
    id            VARCHAR(36) PRIMARY KEY,
    custom_id     VARCHAR(20) UNIQUE,
    driver_id     VARCHAR(36)    NOT NULL,
    type          VARCHAR(30)    NOT NULL,
    amount        NUMERIC(12, 2) NOT NULL,
    balance_after NUMERIC(12, 2) NOT NULL,
    booking_id    VARCHAR(36),
    reference     VARCHAR(100),
    note          VARCHAR(255),
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_driver_debt_entries_driver ON payment.driver_debt_entries (driver_id, created_at);
