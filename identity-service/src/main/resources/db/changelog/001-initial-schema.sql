--liquibase formatted sql

--changeset coryd:001-users
CREATE TABLE identity.users
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    phone      VARCHAR(20) NOT NULL UNIQUE,
    full_name  VARCHAR(100),
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);

--changeset coryd:002-user-roles
CREATE TABLE identity.user_roles
(
    user_id VARCHAR(36)        NOT NULL REFERENCES identity.users (id),
    role    VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_id, role)
);

--changeset coryd:003-otp-codes
CREATE TABLE identity.otp_codes
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    phone      VARCHAR(20) NOT NULL,
    code       VARCHAR(6)  NOT NULL,
    expires_at TIMESTAMP   NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);
CREATE INDEX idx_otp_codes_phone ON identity.otp_codes (phone);

--changeset coryd:004-refresh-tokens
CREATE TABLE identity.refresh_tokens
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    user_id    VARCHAR(36)         NOT NULL REFERENCES identity.users (id),
    token      VARCHAR(512) NOT NULL UNIQUE,
    expires_at TIMESTAMP    NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
