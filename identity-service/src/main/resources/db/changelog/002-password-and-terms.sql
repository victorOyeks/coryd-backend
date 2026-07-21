--liquibase formatted sql

--changeset coryd:002-password-and-terms
ALTER TABLE identity.users ADD COLUMN password_hash VARCHAR(255);
ALTER TABLE identity.users ADD COLUMN terms_accepted_at TIMESTAMP;
