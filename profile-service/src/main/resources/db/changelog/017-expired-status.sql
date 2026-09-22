--liquibase formatted sql

--changeset coryd:024-expired-status
-- The expiry job used to park lapsed documents on PENDING and explain itself in the rejection
-- reason, which read as "never submitted" to every screen. Those rows are recognisable by the
-- reason text the job writes, since nothing else sets a reason on a non-rejected row.
UPDATE profile.driver_documents
SET status = 'EXPIRED'
WHERE status = 'PENDING'
  AND rejection_reason LIKE 'Expired on %';

UPDATE profile.vehicles
SET verification_status = 'EXPIRED'
WHERE verification_status = 'PENDING'
  AND rejection_reason LIKE 'Auto-suspended:%';
