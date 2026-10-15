--liquibase formatted sql

--changeset coryd:047-identity-user-heard-about-us
-- "How did you hear about us?" from signup - which channels bring people in.
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS heard_about_us VARCHAR(30);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS heard_about_us_other VARCHAR(100);
