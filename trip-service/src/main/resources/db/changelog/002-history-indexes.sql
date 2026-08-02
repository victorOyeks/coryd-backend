--liquibase formatted sql

--changeset coryd:012-ratings-ratee-id-index
-- ratingRepository.summarize(rateeIds) filters WHERE ratee_id IN (...) GROUP BY ratee_id -
-- called on every route search, ride-details view, and now every Trip History page load.
-- Without this the ratings table gets a full sequential scan on every one of those calls.
CREATE INDEX idx_ratings_ratee_id ON trip.ratings (ratee_id);

--changeset coryd:013-bookings-history-index
-- Trip History's query filters on rider_id + status and sorts by created_at desc - a composite
-- index covering all three lets Postgres satisfy the whole query (including the sort) from the
-- index directly, instead of combining the separate rider_id/status indexes and sorting after.
CREATE INDEX idx_bookings_rider_status_created ON trip.bookings (rider_id, status, created_at DESC);
