--liquibase formatted sql

-- Drivers check their NIN by number only (no selfie) - their licence check already matches their face.
-- nin_status is that lookup's outcome; nin_fingerprint is a keyed one-way hash of the NIN (never the NIN
-- itself), used to spot the same NIN on two accounts and numbers NIMC already said don't exist.

--changeset coryd:059-driver-nin-lookup
ALTER TABLE profile.driver_profiles ADD COLUMN IF NOT EXISTS nin_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE profile.identity_verifications ADD COLUMN IF NOT EXISTS nin_fingerprint VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_identity_verifications_nin_fingerprint ON profile.identity_verifications (nin_fingerprint);
CREATE TABLE IF NOT EXISTS profile.nin_lookup_misses (
    user_id     VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, fingerprint)
);
