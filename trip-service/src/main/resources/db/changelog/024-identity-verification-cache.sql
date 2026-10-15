--liquibase formatted sql

--changeset coryd:043-identity-verification-cache
-- Each person's NIN + selfie decision, mirrored from profile-service - riders and drivers alike.
CREATE TABLE IF NOT EXISTS trip.identity_verification_cache
(
    user_id    VARCHAR(36) PRIMARY KEY,
    verified   BOOLEAN     NOT NULL,
    decided_at TIMESTAMP
);
