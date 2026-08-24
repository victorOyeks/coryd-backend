--liquibase formatted sql

--changeset coryd:005-vehicle-photo-public-ids
ALTER TABLE profile.vehicles
    ADD COLUMN IF NOT EXISTS exterior_photo_public_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS interior_photo_public_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS ac_photo_public_id       VARCHAR(255);
