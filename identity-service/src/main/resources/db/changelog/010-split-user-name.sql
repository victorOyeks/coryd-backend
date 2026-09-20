--liquibase formatted sql

--changeset coryd:010-identity-split-user-name
-- Names are stored split so an identity check (Prembly/FRSC) can match a surname on its own -
-- a single full_name column can only ever be compared whole. The backfill takes the first word
-- as the given name and the LAST word as the surname, so any middle name is dropped rather than
-- landing in last_name where it would never match a government record.
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS first_name VARCHAR(100);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS last_name  VARCHAR(100);

UPDATE identity.users
SET first_name = split_part(btrim(full_name), ' ', 1),
    last_name  = CASE
                     WHEN strpos(btrim(full_name), ' ') = 0 THEN NULL
                     ELSE reverse(split_part(reverse(btrim(full_name)), ' ', 1))
                 END
WHERE full_name IS NOT NULL
  AND btrim(full_name) <> '';

ALTER TABLE identity.users DROP COLUMN IF EXISTS full_name;
