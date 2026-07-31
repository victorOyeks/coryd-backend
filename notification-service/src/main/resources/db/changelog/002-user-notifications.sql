--liquibase formatted sql

--changeset coryd:004-user-notifications
CREATE TABLE notification.user_notifications
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    user_id    VARCHAR(36)   NOT NULL,
    title      VARCHAR(100)  NOT NULL,
    body       VARCHAR(500)  NOT NULL,
    event_type VARCHAR(100)  NOT NULL,
    read       BOOLEAN       NOT NULL DEFAULT FALSE,
    read_at    TIMESTAMP,
    created_at TIMESTAMP     NOT NULL,
    updated_at TIMESTAMP     NOT NULL
);
CREATE INDEX idx_user_notifications_user_id ON notification.user_notifications (user_id);
