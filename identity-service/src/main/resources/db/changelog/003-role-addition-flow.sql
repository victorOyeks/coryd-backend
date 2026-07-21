--liquibase formatted sql
--changeset coryd:003-role-addition-flow
ALTER TABLE identity.otp_codes ADD COLUMN purpose VARCHAR(50) NOT NULL DEFAULT 'SIGNUP';
ALTER TABLE identity.refresh_tokens ADD COLUMN active_role VARCHAR(30) NOT NULL DEFAULT 'RIDER';
