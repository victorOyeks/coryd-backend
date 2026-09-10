--liquibase formatted sql

--changeset coryd:021-favorite-drivers
CREATE TABLE profile.favorite_drivers
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    rider_id   VARCHAR(36) NOT NULL,
    driver_id  VARCHAR(36) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL,
    UNIQUE (rider_id, driver_id)
);
CREATE INDEX idx_favorite_drivers_rider_id ON profile.favorite_drivers (rider_id);
CREATE INDEX idx_favorite_drivers_driver_id ON profile.favorite_drivers (driver_id);
