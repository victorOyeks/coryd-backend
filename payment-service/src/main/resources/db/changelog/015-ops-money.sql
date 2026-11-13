--liquibase formatted sql

--changeset coryd:110-ops-money-requests
-- Ops Console money actions - a manual refund, a refund or payout retry, a settlement-debt adjustment. Each is a row
-- from the moment it's asked for: one above the approval threshold waits in PENDING_APPROVAL until a different admin
-- approves (then it runs) or rejects it; the requester can cancel it. idempotency_key makes a repeated submit return
-- the first row instead of acting twice.
CREATE TABLE IF NOT EXISTS payment.ops_money_requests
(
    id                 VARCHAR(36) PRIMARY KEY,
    custom_id          VARCHAR(20) UNIQUE,
    type               VARCHAR(30)    NOT NULL,
    status             VARCHAR(30)    NOT NULL,
    amount             NUMERIC(12, 2) NOT NULL,
    direction          VARCHAR(10),
    booking_id         VARCHAR(36),
    rider_id           VARCHAR(36),
    driver_id          VARCHAR(36),
    target_id          VARCHAR(50),
    reason             VARCHAR(500)   NOT NULL,
    idempotency_key    VARCHAR(100),
    needs_approval     BOOLEAN        NOT NULL DEFAULT FALSE,
    requested_by       VARCHAR(36)    NOT NULL,
    requested_by_name  VARCHAR(120),
    decided_by         VARCHAR(36),
    decided_by_name    VARCHAR(120),
    decision_reason    VARCHAR(500),
    decided_at         TIMESTAMP,
    executed_at        TIMESTAMP,
    failure_reason     VARCHAR(500),
    refund_id          VARCHAR(36),
    attempts           INTEGER        NOT NULL DEFAULT 0,
    created_at         TIMESTAMP      NOT NULL,
    updated_at         TIMESTAMP      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ops_money_requests_idempotency ON payment.ops_money_requests (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ops_money_requests_status ON payment.ops_money_requests (status, created_at);
CREATE INDEX IF NOT EXISTS idx_ops_money_requests_booking ON payment.ops_money_requests (booking_id);
CREATE INDEX IF NOT EXISTS idx_ops_money_requests_target ON payment.ops_money_requests (target_id);
CREATE INDEX IF NOT EXISTS idx_ops_money_requests_refund ON payment.ops_money_requests (refund_id);

--changeset coryd:111-ops-money-audit
-- Every ops money action: who, what, on which, how much, the state before and after (JSON), and why. Append-only.
CREATE TABLE IF NOT EXISTS payment.ops_money_audit
(
    id            VARCHAR(36) PRIMARY KEY,
    custom_id     VARCHAR(20) UNIQUE,
    actor_id      VARCHAR(36)    NOT NULL,
    actor_name    VARCHAR(120),
    action        VARCHAR(40)    NOT NULL,
    target_type   VARCHAR(20)    NOT NULL,
    target_id     VARCHAR(50)    NOT NULL,
    request_id    VARCHAR(36),
    amount        NUMERIC(12, 2),
    before_state  TEXT,
    after_state   TEXT,
    reason        VARCHAR(500),
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ops_money_audit_target ON payment.ops_money_audit (target_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ops_money_audit_actor ON payment.ops_money_audit (actor_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ops_money_audit_request ON payment.ops_money_audit (request_id);

--changeset coryd:112-ops-money-transaction-indexes
-- The console's Transactions list and finance dashboard filter these by time.
CREATE INDEX IF NOT EXISTS idx_payment_transactions_created ON payment.payment_transactions (created_at);
CREATE INDEX IF NOT EXISTS idx_payment_transactions_rider ON payment.payment_transactions (rider_id);
CREATE INDEX IF NOT EXISTS idx_refunds_created ON payment.refunds (created_at);
CREATE INDEX IF NOT EXISTS idx_driver_settlements_driver ON payment.driver_settlements (driver_id, settlement_date);
CREATE INDEX IF NOT EXISTS idx_driver_settlements_status ON payment.driver_settlements (status, settlement_date);
CREATE INDEX IF NOT EXISTS idx_earnings_transactions_created ON payment.earnings_transactions (created_at);
