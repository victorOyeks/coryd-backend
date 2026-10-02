--liquibase formatted sql

--changeset coryd:030-route-schedule-fields
-- Expected trip length from Google at posting time - with a buffer, it is how long a route occupies its
-- driver and riders for the overlap rule. Null on older routes, which fall back to a default.
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS estimated_duration_minutes INTEGER;

-- reminder_sent_at stays the rider reminder; the driver gets an earlier one, and a nudge if departure
-- passes without the trip being started.
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS driver_reminder_sent_at TIMESTAMP;
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS start_nudge_sent_at TIMESTAMP;
