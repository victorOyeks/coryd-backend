--liquibase formatted sql

--changeset coryd:025-trip-driver-verification-first-route-at
-- Start of an unverified driver's grace window. Set on their first successful route post, never
-- reset, so re-posting does not extend the deadline.

ALTER TABLE trip.driver_verification_cache
    ADD COLUMN first_route_at TIMESTAMP;

CREATE INDEX idx_driver_verification_cache_first_route_at
    ON trip.driver_verification_cache (first_route_at)
    WHERE approved = FALSE;
