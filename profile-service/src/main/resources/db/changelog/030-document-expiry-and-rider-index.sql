--liquibase formatted sql

--changeset coryd:071-profile-document-expiry-tracker
-- The Document Expiry Tracker: when the driver was last told about a document's expiry, and by whom (an ops admin, or
-- the nightly reminders). The tracker filters and sorts on expires_on.
ALTER TABLE profile.driver_documents ADD COLUMN IF NOT EXISTS last_notified_at TIMESTAMP;
ALTER TABLE profile.driver_documents ADD COLUMN IF NOT EXISTS last_notified_by VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_driver_documents_expires_on ON profile.driver_documents (expires_on);

--changeset coryd:072-profile-expiry-leaves-vehicles-alone
-- An expired document used to mark the driver's car EXPIRED too, and nothing ever undid that: renewing the document
-- left the driver blocked. The document's own EXPIRED status is what holds them now, so those cars go back to what
-- they were: approved when the car was on file before the driver was verified (so it was approved with them), otherwise
-- - a car submitted since, which ops may never have seen - back in review.
UPDATE profile.vehicles v
SET verification_status = CASE
        WHEN EXISTS (SELECT 1 FROM profile.driver_profiles p
                     WHERE p.user_id = v.driver_id AND p.verified_since IS NOT NULL
                       AND (v.submitted_at IS NULL OR v.submitted_at <= p.verified_since))
            THEN 'APPROVED' ELSE 'IN_REVIEW' END,
    rejection_reason = NULL
WHERE v.verification_status = 'EXPIRED' AND v.rejection_reason LIKE 'Auto-suspended:%';

--changeset coryd:073-profile-rider-index
-- One row per rider with everything the Riders Directory filters, sorts and searches on - one indexed query, however
-- many riders. Filled from rider profiles here, trip-service's rider numbers and identity's suspensions.
CREATE TABLE IF NOT EXISTS profile.rider_index (
    rider_id            VARCHAR(36)  PRIMARY KEY,
    ref_key             VARCHAR(36)  NOT NULL,
    first_name          VARCHAR(100),
    last_name           VARCHAR(100),
    name_key            VARCHAR(201),
    photo_url           VARCHAR(255),
    phone               VARCHAR(30),
    phone_digits        VARCHAR(30),
    joined_at           TIMESTAMP,
    suspended_at        TIMESTAMP,
    suspension_reason   VARCHAR(500),
    directory_status    VARCHAR(20)  NOT NULL,
    trips               BIGINT,
    rating_avg          DOUBLE PRECISION,
    rating_count        BIGINT,
    reliability_percent INTEGER,
    reports_filed       BIGINT,
    total_spent         NUMERIC(14, 2),
    stats_at            TIMESTAMP,
    refreshed_at        TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rider_index_status ON profile.rider_index (directory_status);
CREATE INDEX IF NOT EXISTS idx_rider_index_joined ON profile.rider_index (joined_at DESC);
CREATE INDEX IF NOT EXISTS idx_rider_index_ref ON profile.rider_index (ref_key varchar_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_rider_index_trips ON profile.rider_index (trips);
CREATE INDEX IF NOT EXISTS idx_rider_index_rating ON profile.rider_index (rating_avg);
CREATE INDEX IF NOT EXISTS idx_rider_index_spent ON profile.rider_index (total_spent);
CREATE INDEX IF NOT EXISTS idx_rider_index_reliability ON profile.rider_index (reliability_percent);
CREATE INDEX IF NOT EXISTS idx_rider_index_reports ON profile.rider_index (reports_filed);
CREATE INDEX IF NOT EXISTS idx_rider_index_name_trgm ON profile.rider_index USING gin (name_key gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_rider_index_phone_trgm ON profile.rider_index USING gin (phone_digits gin_trgm_ops);
