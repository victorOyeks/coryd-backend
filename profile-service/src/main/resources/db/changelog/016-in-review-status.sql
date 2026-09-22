--liquibase formatted sql

--changeset coryd:023-in-review-status
-- PENDING used to mean both "nothing submitted" and "submitted, waiting on ops". Anything that
-- has actually been submitted and has no ops verdict moves to IN_REVIEW.
--
-- A rejection_reason on a not-rejected row is the expiry job's auto-suspension, which is waiting
-- on the driver to re-upload rather than on ops - those stay PENDING.
UPDATE profile.driver_profiles
SET government_id_status = 'IN_REVIEW'
WHERE government_id_status = 'PENDING'
  AND government_id_url IS NOT NULL;

UPDATE profile.driver_documents
SET status = 'IN_REVIEW'
WHERE status = 'PENDING'
  AND document_url IS NOT NULL
  AND rejection_reason IS NULL;

-- A vehicle row only exists once it has been registered, so there is no unsubmitted case here.
UPDATE profile.vehicles
SET verification_status = 'IN_REVIEW'
WHERE verification_status = 'PENDING'
  AND rejection_reason IS NULL;
