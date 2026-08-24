--liquibase formatted sql

--changeset coryd:008-drop-vehicle-ac-photo
-- Leftover from the initial scaffold - no vehicle onboarding screen ever asked
-- for a photo of the AC unit itself. acConfirmedWorking (a plain boolean) is
-- the only AC-related field an actual design ever called for.
ALTER TABLE profile.vehicles
    DROP COLUMN IF EXISTS ac_photo_url,
    DROP COLUMN IF EXISTS ac_photo_public_id;
