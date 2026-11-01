--liquibase formatted sql

--changeset coryd:070-profile-driver-index
-- One row per driver with everything the Ops Console filters, sorts and searches on, so the Drivers Directory and the
-- Verification Queue are one indexed query each. Filled from the driver records here, and from trip, payment and identity
-- events; DriverIndexBackfill fills it on startup.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS profile.driver_index (
    driver_id                VARCHAR(36)  PRIMARY KEY,
    ref_key                  VARCHAR(36)  NOT NULL,
    first_name               VARCHAR(255),
    last_name                VARCHAR(255),
    name_key                 VARCHAR(255),
    photo_url                VARCHAR(255),
    phone                    VARCHAR(30),
    phone_digits             VARCHAR(30),
    joined_at                TIMESTAMP,
    overall_status           VARCHAR(20),
    resubmitted              BOOLEAN      NOT NULL DEFAULT FALSE,
    suspended_at             TIMESTAMP,
    suspension_reason        VARCHAR(500),
    directory_status         VARCHAR(20)  NOT NULL,
    vehicle_make             VARCHAR(50),
    vehicle_model            VARCHAR(50),
    vehicle_year             INTEGER,
    vehicle_color            VARCHAR(30),
    plate_number             VARCHAR(20),
    plate_key                VARCHAR(20),
    vehicle_key              VARCHAR(110),
    trips                    BIGINT,
    rating_avg               DOUBLE PRECISION,
    rating_count             BIGINT,
    reliability_percent      INTEGER,
    reports_filed            BIGINT,
    stats_at                 TIMESTAMP,
    earnings_net             NUMERIC(14, 2),
    earnings_gross           NUMERIC(14, 2),
    earnings_at              TIMESTAMP,
    in_review                BOOLEAN      NOT NULL DEFAULT FALSE,
    licence_check_started_at TIMESTAMP,
    queue_tab                VARCHAR(20),
    waiting_since            TIMESTAMP,
    submitted_at             TIMESTAMP,
    refreshed_at             TIMESTAMP
);

-- Directory tabs and sortable columns.
CREATE INDEX IF NOT EXISTS idx_driver_index_status ON profile.driver_index (directory_status);
CREATE INDEX IF NOT EXISTS idx_driver_index_joined ON profile.driver_index (joined_at DESC);
CREATE INDEX IF NOT EXISTS idx_driver_index_ref ON profile.driver_index (ref_key varchar_pattern_ops);
CREATE INDEX IF NOT EXISTS idx_driver_index_trips ON profile.driver_index (trips);
CREATE INDEX IF NOT EXISTS idx_driver_index_rating ON profile.driver_index (rating_avg);
CREATE INDEX IF NOT EXISTS idx_driver_index_earnings ON profile.driver_index (earnings_net);
CREATE INDEX IF NOT EXISTS idx_driver_index_reliability ON profile.driver_index (reliability_percent);
-- "contains" search on name, phone and plate.
CREATE INDEX IF NOT EXISTS idx_driver_index_name_trgm ON profile.driver_index USING gin (name_key gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_driver_index_phone_trgm ON profile.driver_index USING gin (phone_digits gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_driver_index_plate_trgm ON profile.driver_index USING gin (plate_key gin_trgm_ops);
-- The Verification Queue only ever reads drivers with something waiting.
CREATE INDEX IF NOT EXISTS idx_driver_index_queue ON profile.driver_index (waiting_since)
    WHERE in_review OR licence_check_started_at IS NOT NULL;
