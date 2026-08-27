--liquibase formatted sql

--changeset coryd:008-widen-corridor-key
-- Full Places-formatted addresses on both ends of a corridor overflow VARCHAR(100), which
-- aborted the whole POST /trip/routes/drivers transaction with SQLSTATE 22001. Both labels
-- are capped at 255 chars (PostRouteRequest), so 512 bounds "origin->destination" exactly.
ALTER TABLE trip.fare_reports ALTER COLUMN corridor_key TYPE VARCHAR(512);
ALTER TABLE trip.suggested_fare_ranges ALTER COLUMN corridor_key TYPE VARCHAR(512);
