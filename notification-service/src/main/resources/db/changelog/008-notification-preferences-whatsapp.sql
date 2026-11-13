--liquibase formatted sql

--changeset coryd:074-notification-preferences-whatsapp
-- Per-user switch for WhatsApp delivery. Off sends the same message by SMS instead; nothing is suppressed.
ALTER TABLE notification.notification_preferences ADD COLUMN IF NOT EXISTS whatsapp_opt_in BOOLEAN NOT NULL DEFAULT TRUE;
