--liquibase formatted sql

--changeset coryd:054-identity-beta-signups
-- The website's beta waitlist - one row per WhatsApp number.
CREATE TABLE IF NOT EXISTS identity.beta_signups
(
    id              VARCHAR(36) PRIMARY KEY,
    custom_id       VARCHAR(20) UNIQUE,
    name            VARCHAR(100) NOT NULL,
    whatsapp_number VARCHAR(20)  NOT NULL UNIQUE,
    will_mostly     VARCHAR(10)  NOT NULL,
    usual_route     VARCHAR(200) NOT NULL,
    device          VARCHAR(10)  NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    invited_at      TIMESTAMP,
    invited_by      VARCHAR(36),
    joined_at       TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_beta_signups_created ON identity.beta_signups (created_at DESC);
