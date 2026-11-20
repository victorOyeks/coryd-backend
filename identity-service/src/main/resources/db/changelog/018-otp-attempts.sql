--liquibase formatted sql

--changeset coryd:182-identity-otp-attempts
-- Wrong guesses against a code. After 5 the code is spent (used = true) and a new one must be requested - without a
-- cap a 6-digit code could be guessed within its 5 minutes by brute force, and reset-password would hand over the account.
ALTER TABLE identity.otp_codes ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;
