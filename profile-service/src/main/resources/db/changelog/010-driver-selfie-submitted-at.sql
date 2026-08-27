--liquibase formatted sql

--changeset coryd:010-driver-selfie-submitted-at
-- Approving a selfie purges selfie_url (the image is only ever needed for that one
-- review), so the URL cannot be used to answer "did the driver submit a selfie?".
-- Record the submission itself.
ALTER TABLE profile.driver_profiles
    ADD COLUMN IF NOT EXISTS selfie_submitted_at TIMESTAMP;

-- Backfill: a row still holding a selfie, or whose selfie has already been reviewed,
-- demonstrably had one submitted. Only PENDING-with-no-URL is genuinely "never sent".
UPDATE profile.driver_profiles
SET selfie_submitted_at = COALESCE(updated_at, created_at)
WHERE selfie_submitted_at IS NULL
  AND (selfie_url IS NOT NULL OR selfie_status IN ('APPROVED', 'REJECTED'));
