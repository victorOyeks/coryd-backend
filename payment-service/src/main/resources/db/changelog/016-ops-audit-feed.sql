--liquibase formatted sql

--changeset coryd:126-ops-money-audit-feed-order
-- The ops console's unified audit log reads money actions newest first.
CREATE INDEX IF NOT EXISTS idx_ops_money_audit_created ON payment.ops_money_audit (created_at DESC, id DESC);
