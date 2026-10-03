--liquibase formatted sql

--changeset coryd:017-user-notification-route-post-id
-- Route notifications (driver reminders, start-or-cancel, route cancelled) deep-link by route, not booking.
ALTER TABLE notification.user_notifications ADD COLUMN IF NOT EXISTS route_post_id VARCHAR(36);
