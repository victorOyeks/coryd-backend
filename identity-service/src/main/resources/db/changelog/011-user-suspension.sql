--liquibase formatted sql

--changeset coryd:038-identity-user-suspension
-- active already existed (unused); these record when and why ops suspended the account.
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMP;
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(500);
