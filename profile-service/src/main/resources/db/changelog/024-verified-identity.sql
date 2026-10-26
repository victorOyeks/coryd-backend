--liquibase formatted sql

-- The name and date of birth NIMC holds for a person, kept once their NIN check is approved. Their profile name
-- is set from these and can't be changed in the app afterwards. Nothing else from the NIN record is kept here.

--changeset coryd:058-verified-identity
ALTER TABLE profile.identity_verifications ADD COLUMN IF NOT EXISTS verified_first_name VARCHAR(100);
ALTER TABLE profile.identity_verifications ADD COLUMN IF NOT EXISTS verified_middle_name VARCHAR(100);
ALTER TABLE profile.identity_verifications ADD COLUMN IF NOT EXISTS verified_last_name VARCHAR(100);
ALTER TABLE profile.identity_verifications ADD COLUMN IF NOT EXISTS verified_date_of_birth DATE;
