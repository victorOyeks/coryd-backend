--liquibase formatted sql

--changeset coryd:006-forgot-password
-- Re-adds otp_codes.purpose, dropped in 004 when the old logged-out
-- role-addition flow (the only other purpose at the time) went away.
-- Forgot Password is a genuinely new second purpose, not a revival of
-- that one - scopes an OTP to SIGNUP vs PASSWORD_RESET so a code issued
-- for one flow can never be replayed against the other.
ALTER TABLE identity.otp_codes ADD COLUMN purpose VARCHAR(50) NOT NULL DEFAULT 'SIGNUP';
