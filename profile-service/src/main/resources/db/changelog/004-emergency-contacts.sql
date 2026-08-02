--liquibase formatted sql

--changeset coryd:004-emergency-contacts
CREATE TABLE profile.emergency_contacts
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    rider_id   VARCHAR(36)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    phone      VARCHAR(20)  NOT NULL,
    is_primary BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
CREATE INDEX idx_emergency_contacts_rider_id ON profile.emergency_contacts (rider_id);
