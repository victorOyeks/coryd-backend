--liquibase formatted sql

--changeset coryd:067-profile-verification-review-marks
-- An ops reviewer's Verified / Flagged on each of a driver's items before the decision is sent, so the marks survive
-- closing the screen. One per driver and item; ignored once the driver re-uploads (item_submitted_at no longer matches).
CREATE TABLE IF NOT EXISTS profile.verification_review_marks (
    id                VARCHAR(36)  PRIMARY KEY,
    custom_id         VARCHAR(20)  UNIQUE,
    driver_id         VARCHAR(36)  NOT NULL,
    item              VARCHAR(30)  NOT NULL,
    mark              VARCHAR(20)  NOT NULL,
    note              VARCHAR(500),
    marked_by         VARCHAR(150) NOT NULL,
    item_submitted_at TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uq_verification_review_marks UNIQUE (driver_id, item)
);

--changeset coryd:068-profile-verification-decision-log
-- Every decision on a driver's item - who, what and why - for the review screen's audit log.
CREATE TABLE IF NOT EXISTS profile.verification_decision_log (
    id         VARCHAR(36)  PRIMARY KEY,
    custom_id  VARCHAR(20)  UNIQUE,
    driver_id  VARCHAR(36)  NOT NULL,
    item       VARCHAR(30)  NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    note       VARCHAR(500),
    decided_by VARCHAR(150) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_verification_decision_log_driver ON profile.verification_decision_log (driver_id, created_at);
