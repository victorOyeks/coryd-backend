--liquibase formatted sql

--changeset coryd:069-profile-licence-decided-during-check
-- Ops can decide a licence while Smile ID's result is still out; when the result comes it overrides ops.
ALTER TABLE profile.driver_profiles ADD COLUMN IF NOT EXISTS government_id_decided_during_check BOOLEAN NOT NULL DEFAULT FALSE;
