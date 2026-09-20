--liquibase formatted sql

--changeset coryd:022-verification-checks
-- One row per (driver, check type, subject) so re-saving the same licence number or plate reuses
-- the answer instead of buying another lookup. The unique constraint is what makes that true
-- under concurrency rather than only in the happy path.
CREATE TABLE profile.verification_checks
(
    id                 VARCHAR(36) PRIMARY KEY,
    custom_id          VARCHAR(20) UNIQUE,
    driver_id          VARCHAR(36) NOT NULL,
    check_type         VARCHAR(30) NOT NULL,
    subject_key        VARCHAR(50) NOT NULL,
    outcome            VARCHAR(20) NOT NULL,
    name_match_score   DOUBLE PRECISION,
    provider_summary   VARCHAR(500),
    provider_reference VARCHAR(100),
    created_at         TIMESTAMP   NOT NULL,
    updated_at         TIMESTAMP   NOT NULL,
    UNIQUE (driver_id, check_type, subject_key)
);
CREATE INDEX idx_verification_checks_driver_id ON profile.verification_checks (driver_id);
