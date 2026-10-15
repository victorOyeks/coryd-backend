--liquibase formatted sql

--changeset coryd:046-notify-me-created-index
-- The pilot metrics count notify-me requests made in a window.
CREATE INDEX IF NOT EXISTS idx_notify_me_requests_created ON trip.notify_me_requests (created_at);
