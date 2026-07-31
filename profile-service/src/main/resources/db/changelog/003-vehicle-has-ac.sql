--liquibase formatted sql

--changeset coryd:003-vehicle-has-ac
ALTER TABLE profile.vehicles
    ADD COLUMN has_ac BOOLEAN NOT NULL DEFAULT FALSE;
