--liquibase formatted sql

--changeset coryd:025-government-id-public-id
-- Approving a government ID nulled nothing and deleted nothing, because there was no public_id to
-- delete by - every approved licence image stayed live in Cloudinary and readable from the API.
ALTER TABLE profile.driver_profiles ADD COLUMN IF NOT EXISTS government_id_public_id VARCHAR(255);
