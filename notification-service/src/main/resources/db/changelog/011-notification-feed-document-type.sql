--liquibase formatted sql

--changeset coryd:184-notification-feed-document-type
-- The bell's DOCUMENT_EXPIRING entries say which document, as the push data does, so tapping one opens that document.
ALTER TABLE notification.user_notifications ADD COLUMN IF NOT EXISTS document_type VARCHAR(30);
