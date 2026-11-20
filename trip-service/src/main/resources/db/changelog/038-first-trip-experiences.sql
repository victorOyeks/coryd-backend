--liquibase formatted sql

--changeset coryd:187-trip-first-trip-experiences
-- A person's first completed trip per role (driver's first drive, rider's first ride) and what they shared about it.
-- Rows are only ever created when such a trip completes after this deploy, so people who already had trips are never
-- asked. One per user and role.
CREATE TABLE IF NOT EXISTS trip.first_trip_experiences
(
    id               VARCHAR(36) PRIMARY KEY,
    custom_id        VARCHAR(20) UNIQUE,
    user_id          VARCHAR(36)  NOT NULL,
    role             VARCHAR(10)  NOT NULL,
    route_post_id    VARCHAR(36)  NOT NULL,
    booking_id       VARCHAR(36),
    status           VARCHAR(10)  NOT NULL,
    feeling          VARCHAR(12),
    story            VARCHAR(500),
    consent_to_share BOOLEAN      NOT NULL DEFAULT FALSE,
    completed_at     TIMESTAMP    NOT NULL,
    responded_at     TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT uq_first_trip_experiences_user_role UNIQUE (user_id, role),
    CONSTRAINT ck_first_trip_experiences_role CHECK (role IN ('DRIVER', 'RIDER')),
    CONSTRAINT ck_first_trip_experiences_status CHECK (status IN ('PENDING', 'SUBMITTED', 'SKIPPED'))
);
CREATE INDEX IF NOT EXISTS idx_first_trip_experiences_shared
    ON trip.first_trip_experiences (responded_at DESC) WHERE status = 'SUBMITTED';
