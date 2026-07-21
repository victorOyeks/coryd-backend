--liquibase formatted sql

--changeset coryd:002-rider-profile-email
ALTER TABLE profile.rider_profiles ADD COLUMN email VARCHAR(255);
