--liquibase formatted sql

--changeset coryd:007-driver-profile-email
ALTER TABLE profile.driver_profiles ADD COLUMN IF NOT EXISTS email VARCHAR(255);
