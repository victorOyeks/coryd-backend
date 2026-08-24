--liquibase formatted sql

--changeset coryd:004-notification-log-email
ALTER TABLE notification.notification_log ADD COLUMN IF NOT EXISTS recipient_email VARCHAR(255);
