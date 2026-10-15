--liquibase formatted sql

--changeset coryd:036-notification-log-push-tracing
-- A push that never showed up on a phone can be traced: whose it was, Firebase's message id, or why Firebase refused it.
ALTER TABLE notification.notification_log ADD COLUMN IF NOT EXISTS user_id VARCHAR(36);
ALTER TABLE notification.notification_log ADD COLUMN IF NOT EXISTS provider_message_id VARCHAR(255);
ALTER TABLE notification.notification_log ADD COLUMN IF NOT EXISTS error_code VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_notification_log_user ON notification.notification_log (user_id, created_at);
