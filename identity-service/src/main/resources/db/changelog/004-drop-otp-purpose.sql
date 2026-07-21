--liquibase formatted sql
--changeset coryd:004-drop-otp-purpose
-- The logged-out password+OTP role-addition flow this column supported was
-- removed - CoRyd is a single app, so adding a role always happens while
-- already authenticated (POST /auth/roles/add), leaving only one OTP purpose
-- (signup verification) ever in use.
ALTER TABLE identity.otp_codes DROP COLUMN purpose;
