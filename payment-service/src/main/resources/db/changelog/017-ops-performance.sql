--liquibase formatted sql

--changeset coryd:160-ops-money-transactions-order
-- The ops Transactions list reads refunds and payouts newest first by when they happened (refunded / settled, else
-- created), one type at a time, and stops at the page - these give it that order without sorting every row.
CREATE INDEX IF NOT EXISTS idx_refunds_occurred ON payment.refunds ((coalesce(refunded_at, created_at)) DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_driver_settlements_occurred ON payment.driver_settlements ((coalesce(settlement_date, created_at)) DESC, id DESC);

--changeset coryd:161-ops-finance-summary-window
-- The finance dashboard sums paid, failed and debt-repayment charges by when they settled (updated_at) over a window -
-- without this every sum read every payment ever taken.
CREATE INDEX IF NOT EXISTS idx_payment_transactions_status_updated ON payment.payment_transactions (status, updated_at);

--changeset coryd:162-ops-money-transactions-amount-order
-- The Transactions list sorted by amount (either way) reads payments and payouts in amount order and stops at the page;
-- refunds are few enough to sort.
CREATE INDEX IF NOT EXISTS idx_payment_transactions_amount ON payment.payment_transactions (amount);
CREATE INDEX IF NOT EXISTS idx_driver_settlements_amount ON payment.driver_settlements (amount);
