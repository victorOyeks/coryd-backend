--liquibase formatted sql

--changeset coryd:185-profile-verified-after-nin
-- A driver is verified only once their identity (NIN) check is approved as well as their four items (while
-- IDENTITY_VERIFICATION_ENFORCEMENT is STRICT, the default). driver_index.nin_pending marks every item approved with the
-- NIN check outstanding ("NIN pending" in the console); rows from before it are NULL, and the startup backfill rebuilds
-- every such row, which also moves those drivers' overall_status off APPROVED.
ALTER TABLE profile.driver_index ADD COLUMN IF NOT EXISTS nin_pending BOOLEAN;

-- verifiedSince is now the first time a driver was fully verified, and guards the one "You're verified!". Drivers told
-- that when their items were approved while their NIN check wasn't get it again, truthfully, when the check is approved.
-- Only drivers clearly not verified: no approved identity check, and not a NIN-lookup driver whose NIN and licence are
-- both approved (DriverIdentity.verified() counts those).
UPDATE profile.driver_profiles dp
SET verified_since = NULL
WHERE dp.verified_since IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM profile.identity_verifications iv
      WHERE iv.user_id = dp.user_id AND iv.status = 'APPROVED'
  )
  AND NOT (dp.nin_status = 'APPROVED' AND dp.government_id_status = 'APPROVED');
