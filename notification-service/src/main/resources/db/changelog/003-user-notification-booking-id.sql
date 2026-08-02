--liquibase formatted sql

--changeset coryd:016-user-notification-booking-id
-- Powers "View details" deep-linking from the notification feed.
ALTER TABLE notification.user_notifications ADD COLUMN booking_id VARCHAR(36);
