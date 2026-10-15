--liquibase formatted sql

--changeset coryd:042-identity-verifications
-- A rider's identity check during onboarding. No full NIN is stored - last four digits only; the
-- selfie is deleted once ops or Prembly decides.
CREATE TABLE IF NOT EXISTS profile.identity_verifications
(
    id               VARCHAR(36) PRIMARY KEY,
    custom_id        VARCHAR(20) UNIQUE,
    user_id          VARCHAR(36)  NOT NULL UNIQUE,
    status           VARCHAR(20)  NOT NULL,
    nin_last4        VARCHAR(4),
    selfie_url       VARCHAR(255),
    selfie_public_id VARCHAR(255),
    consent_at       TIMESTAMP,
    submitted_at     TIMESTAMP,
    decided_at       TIMESTAMP,
    decided_by       VARCHAR(36),
    rejection_reason VARCHAR(500),
    attempts         INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_identity_verifications_status ON profile.identity_verifications (status, submitted_at);
