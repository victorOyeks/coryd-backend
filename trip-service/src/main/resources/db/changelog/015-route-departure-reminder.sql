--liquibase formatted sql

--changeset coryd:027-route-departure-reminder-sent-at
ALTER TABLE trip.route_posts ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMP;
