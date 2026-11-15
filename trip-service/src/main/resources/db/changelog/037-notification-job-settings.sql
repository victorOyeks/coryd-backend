--liquibase formatted sql

--changeset coryd:170-trip-platform-setting-values-wider
-- Onboarding reminders and the verification digest (docs/ops-console/onboarding-reminders.md) add an EMAIL_LIST setting:
-- up to 10 addresses, at most 1000 characters - wider than the 200 a number or a list of minutes needed.
ALTER TABLE trip.platform_settings ALTER COLUMN value TYPE VARCHAR(1000);
ALTER TABLE trip.platform_settings ALTER COLUMN scheduled_value TYPE VARCHAR(1000);
ALTER TABLE trip.platform_setting_changes ALTER COLUMN old_value TYPE VARCHAR(1000);
ALTER TABLE trip.platform_setting_changes ALTER COLUMN new_value TYPE VARCHAR(1000);
