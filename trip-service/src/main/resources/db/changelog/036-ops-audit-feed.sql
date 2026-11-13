--liquibase formatted sql

--changeset coryd:125-trip-ops-audit-log-feed-order
-- The ops console's unified audit log reads trip, booking, SOS, report and rating actions newest first.
CREATE INDEX IF NOT EXISTS idx_ops_audit_log_created ON trip.ops_audit_log (created_at DESC, id DESC);
