--liquibase formatted sql

--changeset coryd:001-rider-profiles
CREATE TABLE profile.rider_profiles
(
    id                      VARCHAR(36) PRIMARY KEY,
    custom_id               VARCHAR(20) UNIQUE,
    user_id                 VARCHAR(36)        NOT NULL UNIQUE,
    full_name               VARCHAR(100),
    photo_url               TEXT,
    emergency_contact_name  VARCHAR(100),
    emergency_contact_phone VARCHAR(20),
    created_at              TIMESTAMP   NOT NULL,
    updated_at              TIMESTAMP   NOT NULL
);

--changeset coryd:002-driver-profiles
CREATE TABLE profile.driver_profiles
(
    id                   VARCHAR(36) PRIMARY KEY,
    custom_id            VARCHAR(20) UNIQUE,
    user_id              VARCHAR(36)        NOT NULL UNIQUE,
    legal_name           VARCHAR(100),
    photo_url            TEXT,
    government_id_url    TEXT,
    verification_status  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at           TIMESTAMP   NOT NULL,
    updated_at           TIMESTAMP   NOT NULL
);

--changeset coryd:003-vehicles
CREATE TABLE profile.vehicles
(
    id                   VARCHAR(36) PRIMARY KEY,
    custom_id            VARCHAR(20) UNIQUE,
    driver_id            VARCHAR(36)        NOT NULL,
    make                 VARCHAR(50),
    model                VARCHAR(50),
    year                 INTEGER,
    plate_number         VARCHAR(20) NOT NULL UNIQUE,
    seat_capacity        INTEGER     NOT NULL,
    exterior_photo_url   TEXT,
    interior_photo_url   TEXT,
    ac_photo_url         TEXT,
    verification_status  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason     TEXT,
    created_at           TIMESTAMP   NOT NULL,
    updated_at           TIMESTAMP   NOT NULL
);
CREATE INDEX idx_vehicles_driver_id ON profile.vehicles (driver_id);

--changeset coryd:004-driver-documents
CREATE TABLE profile.driver_documents
(
    id                VARCHAR(36) PRIMARY KEY,
    custom_id         VARCHAR(20) UNIQUE,
    driver_id         VARCHAR(36)        NOT NULL,
    document_type     VARCHAR(30) NOT NULL,
    document_url      TEXT        NOT NULL,
    expires_on        DATE,
    reminder_sent_14d BOOLEAN     NOT NULL DEFAULT FALSE,
    reminder_sent_3d  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP   NOT NULL,
    updated_at        TIMESTAMP   NOT NULL
);
CREATE INDEX idx_driver_documents_driver_id ON profile.driver_documents (driver_id);
CREATE INDEX idx_driver_documents_expires_on ON profile.driver_documents (expires_on);

--changeset coryd:005-verification-cases
CREATE TABLE profile.verification_cases
(
    id                VARCHAR(36) PRIMARY KEY,
    custom_id         VARCHAR(20) UNIQUE,
    driver_id         VARCHAR(36)        NOT NULL,
    vehicle_id        VARCHAR(36),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason  TEXT,
    reviewed_by       VARCHAR(100),
    reviewed_at       TIMESTAMP,
    created_at        TIMESTAMP   NOT NULL,
    updated_at        TIMESTAMP   NOT NULL
);

--changeset coryd:006-saved-addresses
CREATE TABLE profile.saved_addresses
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    rider_id   VARCHAR(36)             NOT NULL,
    label      VARCHAR(255)     NOT NULL,
    latitude   DOUBLE PRECISION NOT NULL,
    longitude  DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMP        NOT NULL,
    updated_at TIMESTAMP        NOT NULL
);
CREATE INDEX idx_saved_addresses_rider_id ON profile.saved_addresses (rider_id);
