--liquibase formatted sql

--changeset coryd:014-profile-split-names
-- Mirrors identity.users: names split so a verification provider can match the surname on its
-- own. The backfill takes the first word as the given name and the LAST word as the surname,
-- dropping any middle name rather than leaving it in last_name where it would never match a
-- government record.
ALTER TABLE profile.driver_profiles ADD COLUMN IF NOT EXISTS first_name VARCHAR(100);
ALTER TABLE profile.driver_profiles ADD COLUMN IF NOT EXISTS last_name  VARCHAR(100);

UPDATE profile.driver_profiles
SET first_name = split_part(btrim(legal_name), ' ', 1),
    last_name  = CASE
                     WHEN strpos(btrim(legal_name), ' ') = 0 THEN NULL
                     ELSE reverse(split_part(reverse(btrim(legal_name)), ' ', 1))
                 END
WHERE legal_name IS NOT NULL
  AND btrim(legal_name) <> '';

ALTER TABLE profile.driver_profiles DROP COLUMN IF EXISTS legal_name;

ALTER TABLE profile.rider_profiles ADD COLUMN IF NOT EXISTS first_name VARCHAR(100);
ALTER TABLE profile.rider_profiles ADD COLUMN IF NOT EXISTS last_name  VARCHAR(100);

UPDATE profile.rider_profiles
SET first_name = split_part(btrim(full_name), ' ', 1),
    last_name  = CASE
                     WHEN strpos(btrim(full_name), ' ') = 0 THEN NULL
                     ELSE reverse(split_part(reverse(btrim(full_name)), ' ', 1))
                 END
WHERE full_name IS NOT NULL
  AND btrim(full_name) <> '';

ALTER TABLE profile.rider_profiles DROP COLUMN IF EXISTS full_name;
