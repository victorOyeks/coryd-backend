--liquibase formatted sql

--changeset coryd:006-driver-verification-items
ALTER TABLE profile.driver_profiles
    ADD COLUMN IF NOT EXISTS date_of_birth               DATE,
    ADD COLUMN IF NOT EXISTS id_number                    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS government_id_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS government_id_rejection_reason VARCHAR(255),
    ADD COLUMN IF NOT EXISTS selfie_url                    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS selfie_public_id              VARCHAR(255),
    ADD COLUMN IF NOT EXISTS selfie_status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS selfie_rejection_reason       VARCHAR(255),
    DROP COLUMN IF EXISTS verification_status;

ALTER TABLE profile.driver_documents
    ADD COLUMN IF NOT EXISTS vehicle_id          VARCHAR(255),
    ADD COLUMN IF NOT EXISTS document_public_id  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS rejection_reason    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS reviewed_by         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS reviewed_at         TIMESTAMP;

ALTER TABLE profile.vehicles
    ADD COLUMN IF NOT EXISTS color                VARCHAR(30),
    ADD COLUMN IF NOT EXISTS ac_confirmed_working  BOOLEAN NOT NULL DEFAULT FALSE;
