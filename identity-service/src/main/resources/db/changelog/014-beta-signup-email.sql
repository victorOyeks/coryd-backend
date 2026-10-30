--liquibase formatted sql

--changeset coryd:060-identity-beta-signup-email
-- The address ops adds to TestFlight / Play testing. Nullable: people who signed up before the form
-- asked for it have none.
ALTER TABLE identity.beta_signups ADD COLUMN IF NOT EXISTS email VARCHAR(254);
