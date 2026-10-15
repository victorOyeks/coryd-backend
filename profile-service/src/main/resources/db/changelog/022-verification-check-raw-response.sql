--liquibase formatted sql

--changeset coryd:053-verification-check-raw-response
-- Prembly's response behind each check, images stripped - for audit and for disputing a result with
-- Prembly. Holds full NINs, birthdates, phones and addresses: read it from the database, never serve it.
ALTER TABLE profile.verification_checks ADD COLUMN IF NOT EXISTS raw_response JSONB;
