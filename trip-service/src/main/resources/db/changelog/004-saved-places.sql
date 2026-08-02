--liquibase formatted sql

--changeset coryd:015-saved-places
CREATE TABLE trip.saved_places
(
    id                VARCHAR(36)      PRIMARY KEY,
    custom_id         VARCHAR(20)      UNIQUE,
    rider_id          VARCHAR(36)      NOT NULL,
    label             VARCHAR(100)     NOT NULL,
    formatted_address VARCHAR(255)     NOT NULL,
    lat               DOUBLE PRECISION NOT NULL,
    lng               DOUBLE PRECISION NOT NULL,
    type              VARCHAR(20)      NOT NULL DEFAULT 'GENERAL',
    created_at        TIMESTAMP        NOT NULL,
    updated_at        TIMESTAMP        NOT NULL
);
CREATE INDEX idx_saved_places_rider_id ON trip.saved_places (rider_id);
