--liquibase formatted sql
--changeset coryd:005-phone-verified
ALTER TABLE identity.users ADD COLUMN phone_verified_at TIMESTAMP;
