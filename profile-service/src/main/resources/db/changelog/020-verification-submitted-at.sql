--liquibase formatted sql

--changeset coryd:029-verification-submitted-at
-- When each reviewable item last entered review, and whether that was a re-upload after a rejection -
-- the verification queue's "submitted", "in queue for" and Resubmitted tab.
ALTER TABLE profile.driver_profiles ADD COLUMN IF NOT EXISTS government_id_submitted_at TIMESTAMP;
ALTER TABLE profile.driver_profiles ADD COLUMN IF NOT EXISTS government_id_resubmitted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE profile.vehicles ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP;
ALTER TABLE profile.vehicles ADD COLUMN IF NOT EXISTS resubmitted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE profile.driver_documents ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP;
ALTER TABLE profile.driver_documents ADD COLUMN IF NOT EXISTS resubmitted BOOLEAN NOT NULL DEFAULT FALSE;

-- Items already waiting keep their place: their last update is the best record of when they arrived.
UPDATE profile.driver_profiles SET government_id_submitted_at = updated_at
WHERE government_id_status = 'IN_REVIEW' AND government_id_submitted_at IS NULL;
UPDATE profile.vehicles SET submitted_at = updated_at
WHERE verification_status = 'IN_REVIEW' AND submitted_at IS NULL;
UPDATE profile.driver_documents SET submitted_at = updated_at
WHERE status = 'IN_REVIEW' AND submitted_at IS NULL;
