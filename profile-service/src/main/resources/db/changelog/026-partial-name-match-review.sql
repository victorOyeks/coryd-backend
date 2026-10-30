--liquibase formatted sql

--changeset coryd:061-profile-partial-name-match-review
-- Identity and licence checks approved on a partial name match (outcome PARTIAL_NAME_MATCH) are listed for ops
-- until someone marks them reviewed.
ALTER TABLE profile.verification_checks ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;
ALTER TABLE profile.verification_checks ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_verification_checks_unreviewed_partial
    ON profile.verification_checks (created_at) WHERE outcome = 'PARTIAL_NAME_MATCH' AND reviewed_at IS NULL;
