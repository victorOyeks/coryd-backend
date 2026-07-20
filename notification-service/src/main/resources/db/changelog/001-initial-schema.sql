--liquibase formatted sql

--changeset coryd:001-device-tokens
CREATE TABLE notification.device_tokens
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    user_id    VARCHAR(36)         NOT NULL,
    fcm_token  VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
CREATE INDEX idx_device_tokens_user_id ON notification.device_tokens (user_id);

--changeset coryd:002-notification-preferences
CREATE TABLE notification.notification_preferences
(
    id                  VARCHAR(36) PRIMARY KEY,
    custom_id           VARCHAR(20) UNIQUE,
    user_id             VARCHAR(36)      NOT NULL UNIQUE,
    promotional_opt_in  BOOLEAN   NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL
);

--changeset coryd:003-notification-log
CREATE TABLE notification.notification_log
(
    id               VARCHAR(36) PRIMARY KEY,
    custom_id        VARCHAR(20) UNIQUE,
    recipient_phone  VARCHAR(20),
    channel          VARCHAR(20)  NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    delivered        BOOLEAN      NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);
