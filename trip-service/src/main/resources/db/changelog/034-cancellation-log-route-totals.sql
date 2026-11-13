--liquibase formatted sql

--changeset coryd:093-trip-cancellation-log-backfilled-route-totals
-- 092 rebuilt each older cancelled trip's ROUTE row and its paid seats' BOOKING rows, but left the ROUTE row unpaid with no
-- totals, so the log read "Not paid" for a trip whose seats were paid and refunded. Roll the seats up into their trip row,
-- the way CancellationLogService.addToRoute does for new cancellations. Only backfilled rows; idempotent.
UPDATE trip.cancellation_log r
SET seat_count        = s.seats,
    fare_total        = s.fare,
    fee_amount        = s.fee,
    refund_expected   = s.expected,
    bookings_affected = s.n,
    paid              = s.any_paid,
    updated_at        = NOW()
FROM (SELECT b.parent_id,
             COUNT(*)                          AS n,
             COALESCE(SUM(b.seat_count), 0)    AS seats,
             COALESCE(SUM(b.fare_total), 0)    AS fare,
             COALESCE(SUM(b.fee_amount), 0)    AS fee,
             COALESCE(SUM(b.refund_expected), 0) AS expected,
             BOOL_OR(b.paid)                   AS any_paid
      FROM trip.cancellation_log b
      WHERE b.kind = 'BOOKING' AND b.parent_id IS NOT NULL
      GROUP BY b.parent_id) s
WHERE r.id = s.parent_id
  AND r.kind = 'ROUTE'
  AND r.backfilled = TRUE;
