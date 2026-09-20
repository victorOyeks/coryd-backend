--liquibase formatted sql

--changeset coryd:027-profile-drop-driver-selfie
-- Selfie review is dropped: identity is verified against the driver's licence via Prembly rather
-- than by a human comparing a selfie to an ID photo.

ALTER TABLE profile.driver_profiles
    DROP COLUMN IF EXISTS selfie_url,
    DROP COLUMN IF EXISTS selfie_public_id,
    DROP COLUMN IF EXISTS selfie_status,
    DROP COLUMN IF EXISTS selfie_rejection_reason,
    DROP COLUMN IF EXISTS selfie_submitted_at;
