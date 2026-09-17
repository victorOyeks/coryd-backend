--liquibase formatted sql

--changeset coryd:026-trip-driver-prerequisites-met-at
-- Identity and vehicle details cannot be un-submitted, so once the prerequisite check passes it is
-- cached here and profile-service is never asked again for that driver.

ALTER TABLE trip.driver_verification_cache
    ADD COLUMN prerequisites_met_at TIMESTAMP;
