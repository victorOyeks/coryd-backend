--liquibase formatted sql

--changeset coryd:055-smile-id-checks
-- A person's Smile ID check of one kind - NIN (Biometric KYC) or DRIVERS_LICENCE (Document Verification):
-- the job the app is submitting (results are matched to it), when it was opened (a job with no result
-- after a while counts as abandoned), why an automatic failure needs a retry, and how many tries are used.
CREATE TABLE IF NOT EXISTS profile.smile_id_checks
(
    id           VARCHAR(36) PRIMARY KEY,
    custom_id    VARCHAR(20) UNIQUE,
    user_id      VARCHAR(36) NOT NULL,
    check_type   VARCHAR(20) NOT NULL,
    job_id       VARCHAR(50) UNIQUE,
    started_at   TIMESTAMP,
    retry_reason VARCHAR(30),
    attempts     INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMP   NOT NULL,
    updated_at   TIMESTAMP   NOT NULL,
    CONSTRAINT uq_smile_id_checks_user_type UNIQUE (user_id, check_type)
);
