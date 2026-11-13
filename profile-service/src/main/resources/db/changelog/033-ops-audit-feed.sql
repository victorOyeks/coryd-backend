--liquibase formatted sql

--changeset coryd:124-profile-decision-log-audit-order
-- The ops console's unified audit log reads verification decisions newest first across everyone.
CREATE INDEX IF NOT EXISTS idx_verification_decision_log_created ON profile.verification_decision_log (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_verification_decision_log_decider ON profile.verification_decision_log (decided_by_id, created_at);
