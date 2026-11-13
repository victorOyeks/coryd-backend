--liquibase formatted sql

--changeset coryd:090-trip-cancellation-log
-- Every cancellation in one place for the Ops Console's Cancellations Log: a ROUTE row when a whole trip is called off,
-- and a BOOKING row for every paid seat that ends - by the rider, the driver, the never-started sweep, a payment that
-- landed too late, a suspension, a closed account or ops. Unpaid holds that lapse aren't cancellations and aren't here.
-- A booking can only end once, so it appears at most once.
CREATE TABLE IF NOT EXISTS trip.cancellation_log (
    id                       VARCHAR(36)    PRIMARY KEY,
    custom_id                VARCHAR(20)    UNIQUE,
    kind                     VARCHAR(10)    NOT NULL,
    route_post_id            VARCHAR(36)    NOT NULL,
    booking_id               VARCHAR(36),
    parent_id                VARCHAR(36),
    rider_id                 VARCHAR(36),
    driver_id                VARCHAR(36),
    source                   VARCHAR(20)    NOT NULL,
    cancelled_by             VARCHAR(20),
    actor_id                 VARCHAR(36),
    actor_name               VARCHAR(120),
    reason                   VARCHAR(500),
    fault                    VARCHAR(10),
    cancelled_at             TIMESTAMP      NOT NULL,
    departure_time           TIMESTAMP      NOT NULL,
    minutes_before_departure INTEGER        NOT NULL,
    route_status_before      VARCHAR(20),
    booking_status_before    VARCHAR(20),
    seat_count               INTEGER,
    fare_total               NUMERIC(10, 2),
    paid                     BOOLEAN        NOT NULL DEFAULT FALSE,
    fee_amount               NUMERIC(10, 2) NOT NULL DEFAULT 0,
    refund_expected          NUMERIC(10, 2) NOT NULL DEFAULT 0,
    bookings_affected        INTEGER        NOT NULL DEFAULT 0,
    origin_label             VARCHAR(255),
    destination_label        VARCHAR(255),
    backfilled               BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMP      NOT NULL,
    updated_at               TIMESTAMP      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cancellation_log_booking ON trip.cancellation_log (booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cancellation_log_cancelled_at ON trip.cancellation_log (cancelled_at);
CREATE INDEX IF NOT EXISTS idx_cancellation_log_route ON trip.cancellation_log (route_post_id);
CREATE INDEX IF NOT EXISTS idx_cancellation_log_rider ON trip.cancellation_log (rider_id);
CREATE INDEX IF NOT EXISTS idx_cancellation_log_driver ON trip.cancellation_log (driver_id);

--changeset coryd:091-trip-ops-audit-log
-- Who in ops did what to which trip or booking, what it looked like before and after, and why.
CREATE TABLE IF NOT EXISTS trip.ops_audit_log (
    id            VARCHAR(36)  PRIMARY KEY,
    custom_id     VARCHAR(20)  UNIQUE,
    actor_id      VARCHAR(36)  NOT NULL,
    actor_name    VARCHAR(120),
    action        VARCHAR(40)  NOT NULL,
    target_type   VARCHAR(10)  NOT NULL,
    target_id     VARCHAR(36)  NOT NULL,
    route_post_id VARCHAR(36)  NOT NULL,
    before_state  TEXT,
    after_state   TEXT,
    reason        VARCHAR(500) NOT NULL,
    fault         VARCHAR(10),
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ops_audit_log_route ON trip.ops_audit_log (route_post_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ops_audit_log_actor ON trip.ops_audit_log (actor_id, created_at);

--changeset coryd:092-trip-cancellation-log-backfill
-- Cancellations from before the log existed, rebuilt from what the tables still say. Best effort and marked backfilled:
-- a route cancelled with no start 40+ minutes after its departure is taken as the never-started sweep, anything else
-- as its driver; a paid seat cancelled on a live route is the late-payment refund. Reasons weren't recorded then.
INSERT INTO trip.cancellation_log (id, kind, route_post_id, driver_id, source, cancelled_by, fault, cancelled_at, departure_time,
                                   minutes_before_departure, route_status_before, paid, bookings_affected, origin_label,
                                   destination_label, backfilled, created_at, updated_at)
SELECT md5('cancellation-route:' || r.id)::uuid::text,
       'ROUTE', r.id, r.driver_id,
       CASE WHEN r.started_at IS NULL AND r.updated_at >= r.departure_time + INTERVAL '40 minutes' THEN 'SYSTEM_EXPIRY' ELSE 'DRIVER' END,
       CASE WHEN r.started_at IS NULL AND r.updated_at >= r.departure_time + INTERVAL '40 minutes' THEN 'NOT_STARTED' ELSE 'DRIVER' END,
       'DRIVER', r.updated_at, r.departure_time,
       CAST(FLOOR(EXTRACT(EPOCH FROM (r.departure_time - r.updated_at)) / 60) AS INTEGER),
       CASE WHEN r.started_at IS NULL THEN NULL ELSE 'IN_PROGRESS' END,
       FALSE,
       (SELECT COUNT(*) FROM trip.bookings b WHERE b.route_post_id = r.id AND b.status = 'CANCELLED_DRIVER' AND b.confirmed_at IS NOT NULL),
       r.origin_label, r.destination_label, TRUE, NOW(), NOW()
FROM trip.route_posts r
WHERE r.status = 'CANCELLED'
  AND NOT EXISTS (SELECT 1 FROM trip.cancellation_log c WHERE c.kind = 'ROUTE' AND c.route_post_id = r.id);

INSERT INTO trip.cancellation_log (id, kind, route_post_id, booking_id, parent_id, rider_id, driver_id, source, cancelled_by, fault,
                                   cancelled_at, departure_time, minutes_before_departure, booking_status_before, seat_count,
                                   fare_total, paid, fee_amount, refund_expected, origin_label, destination_label, backfilled,
                                   created_at, updated_at)
SELECT md5('cancellation-booking:' || x.booking_id)::uuid::text,
       'BOOKING', x.route_post_id, x.booking_id, x.parent_id, x.rider_id, x.driver_id, x.source, x.cancelled_by, x.fault,
       x.cancelled_at, x.departure_time, CAST(FLOOR(EXTRACT(EPOCH FROM (x.departure_time - x.cancelled_at)) / 60) AS INTEGER),
       NULL, x.seat_count, x.fare_total, TRUE, x.fee, x.fare_total - x.fee, x.origin_label, x.destination_label, TRUE, NOW(), NOW()
FROM (
    SELECT b.id AS booking_id, b.route_post_id, b.rider_id, r.driver_id, b.seat_count, b.fare_total, r.departure_time,
           r.origin_label, r.destination_label,
           COALESCE(h.created_at, b.updated_at) AS cancelled_at,
           CASE WHEN b.status = 'CANCELLED_DRIVER' AND r.status = 'CANCELLED' THEN rc.id END AS parent_id,
           CASE
               WHEN b.status = 'CANCELLED_RIDER' AND h.note LIKE 'Rider suspended%' THEN 'SUSPENSION'
               WHEN b.status = 'CANCELLED_RIDER' AND h.note LIKE 'Rider closed%' THEN 'ACCOUNT_DELETED'
               WHEN b.status = 'CANCELLED_RIDER' THEN 'RIDER'
               WHEN r.status = 'CANCELLED' THEN COALESCE(rc.source, 'DRIVER')
               ELSE 'SYSTEM'
           END AS source,
           CASE
               WHEN b.status = 'CANCELLED_RIDER' AND h.note LIKE 'Rider suspended%' THEN 'SUSPENDED'
               WHEN b.status = 'CANCELLED_RIDER' AND h.note LIKE 'Rider closed%' THEN 'ACCOUNT_DELETED'
               WHEN b.status = 'CANCELLED_RIDER' THEN 'RIDER'
               WHEN r.status = 'CANCELLED' THEN COALESCE(rc.cancelled_by, 'DRIVER')
               ELSE 'SYSTEM'
           END AS cancelled_by,
           CASE
               WHEN b.status = 'CANCELLED_RIDER' AND (h.note LIKE 'Rider suspended%' OR h.note LIKE 'Rider closed%') THEN NULL
               WHEN b.status = 'CANCELLED_RIDER' THEN 'RIDER'
               WHEN r.status = 'CANCELLED' THEN 'DRIVER'
               ELSE 'PLATFORM'
           END AS fault,
           CASE WHEN b.status = 'CANCELLED_RIDER' AND h.note = 'Cancelled inside free window'
                THEN ROUND(b.fare_total * 0.25, 2) ELSE 0 END AS fee
    FROM trip.bookings b
    JOIN trip.route_posts r ON r.id = b.route_post_id
    LEFT JOIN trip.cancellation_log rc ON rc.kind = 'ROUTE' AND rc.route_post_id = r.id
    LEFT JOIN LATERAL (
        SELECT bh.created_at, bh.note FROM trip.booking_status_history bh
        WHERE bh.booking_id = b.id AND bh.status = b.status
        ORDER BY bh.created_at DESC LIMIT 1
    ) h ON TRUE
    WHERE b.status IN ('CANCELLED_RIDER', 'CANCELLED_DRIVER')
      AND (b.confirmed_at IS NOT NULL OR (b.status = 'CANCELLED_DRIVER' AND r.status <> 'CANCELLED'))
      AND (h.note IS NULL OR h.note NOT LIKE '%seat released%')
) x
WHERE NOT EXISTS (SELECT 1 FROM trip.cancellation_log c WHERE c.booking_id = x.booking_id);
