--liquibase formatted sql

--changeset coryd:001-route-posts
CREATE TABLE trip.route_posts
(
    id                     VARCHAR(36) PRIMARY KEY,
    custom_id              VARCHAR(20) UNIQUE,
    driver_id              VARCHAR(36)             NOT NULL,
    vehicle_id             VARCHAR(36),
    origin_label           VARCHAR(255)     NOT NULL,
    origin_lat             DOUBLE PRECISION NOT NULL,
    origin_lng             DOUBLE PRECISION NOT NULL,
    destination_label      VARCHAR(255)     NOT NULL,
    destination_lat        DOUBLE PRECISION NOT NULL,
    destination_lng        DOUBLE PRECISION NOT NULL,
    departure_time         TIMESTAMP        NOT NULL,
    seat_capacity          INTEGER          NOT NULL,
    seats_available        INTEGER          NOT NULL,
    fare_per_seat          NUMERIC(10, 2)   NOT NULL,
    status                 VARCHAR(20)      NOT NULL DEFAULT 'PUBLISHED',
    first_booking_locked_at TIMESTAMP,
    created_at             TIMESTAMP        NOT NULL,
    updated_at             TIMESTAMP        NOT NULL
);
CREATE INDEX idx_route_posts_driver_id ON trip.route_posts (driver_id);
CREATE INDEX idx_route_posts_departure_time ON trip.route_posts (departure_time);
CREATE INDEX idx_route_posts_status ON trip.route_posts (status);

--changeset coryd:002-route-templates
CREATE TABLE trip.route_templates
(
    id                 VARCHAR(36) PRIMARY KEY,
    custom_id          VARCHAR(20) UNIQUE,
    driver_id          VARCHAR(36)             NOT NULL,
    origin_label       VARCHAR(255)     NOT NULL,
    origin_lat         DOUBLE PRECISION NOT NULL,
    origin_lng         DOUBLE PRECISION NOT NULL,
    destination_label  VARCHAR(255)     NOT NULL,
    destination_lat    DOUBLE PRECISION NOT NULL,
    destination_lng    DOUBLE PRECISION NOT NULL,
    typical_seat_count INTEGER          NOT NULL,
    created_at         TIMESTAMP        NOT NULL,
    updated_at         TIMESTAMP        NOT NULL
);

--changeset coryd:003-fare-reports
CREATE TABLE trip.fare_reports
(
    id            VARCHAR(36) PRIMARY KEY,
    custom_id     VARCHAR(20) UNIQUE,
    corridor_key  VARCHAR(100)   NOT NULL,
    fare_per_seat NUMERIC(10, 2) NOT NULL,
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP      NOT NULL
);
CREATE INDEX idx_fare_reports_corridor_key ON trip.fare_reports (corridor_key);

--changeset coryd:004-suggested-fare-ranges
CREATE TABLE trip.suggested_fare_ranges
(
    id           VARCHAR(36) PRIMARY KEY,
    custom_id    VARCHAR(20) UNIQUE,
    corridor_key VARCHAR(100)   NOT NULL UNIQUE,
    min_fare     NUMERIC(10, 2) NOT NULL,
    max_fare     NUMERIC(10, 2) NOT NULL,
    sample_size  INTEGER        NOT NULL,
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);

--changeset coryd:005-notify-me-requests
CREATE TABLE trip.notify_me_requests
(
    id                     VARCHAR(36) PRIMARY KEY,
    custom_id              VARCHAR(20) UNIQUE,
    rider_id               VARCHAR(36)             NOT NULL,
    origin_lat             DOUBLE PRECISION NOT NULL,
    origin_lng             DOUBLE PRECISION NOT NULL,
    destination_lat        DOUBLE PRECISION NOT NULL,
    destination_lng        DOUBLE PRECISION NOT NULL,
    desired_departure_time TIMESTAMP        NOT NULL,
    expires_at             TIMESTAMP        NOT NULL,
    fulfilled              BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMP        NOT NULL,
    updated_at             TIMESTAMP        NOT NULL
);

--changeset coryd:006-bookings
CREATE TABLE trip.bookings
(
    id                     VARCHAR(36) PRIMARY KEY,
    custom_id              VARCHAR(20) UNIQUE,
    route_post_id          VARCHAR(36)           NOT NULL,
    rider_id               VARCHAR(36)           NOT NULL,
    seat_count             INTEGER        NOT NULL,
    fare_total             NUMERIC(10, 2) NOT NULL,
    status                 VARCHAR(20)    NOT NULL DEFAULT 'HELD',
    hold_expires_at        TIMESTAMP      NOT NULL,
    pickup_confirmed_at    TIMESTAMP,
    driver_name_snapshot   VARCHAR(100),
    vehicle_plate_snapshot VARCHAR(20),
    route_summary_snapshot VARCHAR(500),
    fare_per_seat_snapshot NUMERIC(10, 2),
    created_at             TIMESTAMP      NOT NULL,
    updated_at             TIMESTAMP      NOT NULL
);
CREATE INDEX idx_bookings_route_post_id ON trip.bookings (route_post_id);
CREATE INDEX idx_bookings_rider_id ON trip.bookings (rider_id);
CREATE INDEX idx_bookings_status ON trip.bookings (status);

--changeset coryd:007-booking-status-history
CREATE TABLE trip.booking_status_history
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    booking_id VARCHAR(36)        NOT NULL,
    status     VARCHAR(20) NOT NULL,
    note       VARCHAR(500),
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);
CREATE INDEX idx_booking_status_history_booking_id ON trip.booking_status_history (booking_id);

--changeset coryd:008-ratings
CREATE TABLE trip.ratings
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    booking_id VARCHAR(36)        NOT NULL,
    rater_id   VARCHAR(36)        NOT NULL,
    ratee_id   VARCHAR(36)        NOT NULL,
    direction  VARCHAR(20) NOT NULL,
    stars      INTEGER     NOT NULL,
    comment    VARCHAR(1000),
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);

--changeset coryd:009-sos-incidents
CREATE TABLE trip.sos_incidents
(
    id               VARCHAR(36) PRIMARY KEY,
    custom_id        VARCHAR(20) UNIQUE,
    booking_id       VARCHAR(36)             NOT NULL,
    triggered_by     VARCHAR(36)             NOT NULL,
    latitude         DOUBLE PRECISION,
    longitude        DOUBLE PRECISION,
    ops_notified_at  TIMESTAMP,
    created_at       TIMESTAMP        NOT NULL,
    updated_at       TIMESTAMP        NOT NULL
);

--changeset coryd:010-issue-reports
CREATE TABLE trip.issue_reports
(
    id                VARCHAR(36) PRIMARY KEY,
    custom_id         VARCHAR(20) UNIQUE,
    booking_id        VARCHAR(36)         NOT NULL,
    reported_by       VARCHAR(36)         NOT NULL,
    category          VARCHAR(30)  NOT NULL,
    description       VARCHAR(2000) NOT NULL,
    reference_number  VARCHAR(20)  NOT NULL UNIQUE,
    status            VARCHAR(20)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL
);

--changeset coryd:011-driver-verification-cache
CREATE TABLE trip.driver_verification_cache
(
    driver_id VARCHAR(36) PRIMARY KEY,
    approved  BOOLEAN NOT NULL DEFAULT FALSE
);
