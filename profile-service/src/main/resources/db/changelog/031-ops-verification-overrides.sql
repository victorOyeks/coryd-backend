--liquibase formatted sql

--changeset coryd:074-profile-verification-decision-log-audit
-- The verification decision log becomes the audit trail for every ops verification action, on a driver's documents and
-- on anyone's identity (NIN) check (item IDENTITY; driver_id holds the person's user id either way): what was done,
-- the status before and after, who did it (their name in decided_by, their user id in decided_by_id), and whether an
-- approval overrode what Smile ID had said (provider_outcome: its outcome, or the retry it asked for).
-- Older rows keep action NULL - their status says what happened.
ALTER TABLE profile.verification_decision_log ADD COLUMN IF NOT EXISTS action VARCHAR(30);
ALTER TABLE profile.verification_decision_log ADD COLUMN IF NOT EXISTS previous_status VARCHAR(20);
ALTER TABLE profile.verification_decision_log ADD COLUMN IF NOT EXISTS overrode_provider BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE profile.verification_decision_log ADD COLUMN IF NOT EXISTS provider_outcome VARCHAR(40);
ALTER TABLE profile.verification_decision_log ADD COLUMN IF NOT EXISTS decided_by_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_verification_decision_log_driver_item
    ON profile.verification_decision_log (driver_id, item, created_at);

--changeset coryd:075-profile-identity-ops-override
-- An identity check ops approved over Smile ID's result: the note ops left, and that it was an override - shown on the
-- ops identity screen next to the decision. Who and when are decided_by / decided_at, as for any decision.
ALTER TABLE profile.identity_verifications ADD COLUMN IF NOT EXISTS decision_note VARCHAR(500);
ALTER TABLE profile.identity_verifications ADD COLUMN IF NOT EXISTS provider_overridden BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_smile_id_checks_user ON profile.smile_id_checks (user_id, updated_at);
