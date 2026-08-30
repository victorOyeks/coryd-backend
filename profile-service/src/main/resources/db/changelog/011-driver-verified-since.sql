--liquibase formatted sql

--changeset coryd:011-driver-verified-since
-- Set once, the first time a driver reaches overallStatus APPROVED - never moves after that,
-- even through a later rejection + re-approval cycle. "Verified Since" on the Verification
-- Status screen.
ALTER TABLE profile.driver_profiles
    ADD COLUMN IF NOT EXISTS verified_since TIMESTAMP;

-- Backfill: no historical record of exactly when a currently-fully-approved driver first
-- passed, so approximate with their profile's own updated_at rather than leaving them
-- permanently null just because this column didn't exist yet.
UPDATE profile.driver_profiles dp
SET verified_since = dp.updated_at
WHERE dp.verified_since IS NULL
  AND dp.government_id_status = 'APPROVED'
  AND dp.selfie_status = 'APPROVED'
  AND EXISTS (
      SELECT 1 FROM profile.vehicles v
      WHERE v.driver_id = dp.user_id AND v.verification_status = 'APPROVED'
  )
  AND NOT EXISTS (
      SELECT 1 FROM profile.driver_documents dd
      WHERE dd.driver_id = dp.user_id AND dd.status <> 'APPROVED'
  )
  AND EXISTS (
      SELECT 1 FROM profile.driver_documents dd
      WHERE dd.driver_id = dp.user_id AND dd.document_type = 'INSURANCE' AND dd.status = 'APPROVED'
  )
  AND EXISTS (
      SELECT 1 FROM profile.driver_documents dd
      WHERE dd.driver_id = dp.user_id AND dd.document_type = 'ROADWORTHINESS_CERT' AND dd.status = 'APPROVED'
  );
