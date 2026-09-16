--liquibase formatted sql

--changeset coryd:024-identity-normalize-user-phone
-- Riders type the same number as +234.../234.../0.../bare 10 digits. Lookups now canonicalise to
-- the local 11-digit form, so any row stored in another form would stop matching its own owner.
-- Each statement skips a row whose normalised value would collide with an existing account,
-- leaving it for manual review rather than breaking the UNIQUE constraint.

UPDATE identity.users u
SET phone = '0' || right(regexp_replace(u.phone, '[^0-9]', '', 'g'), 10)
WHERE regexp_replace(u.phone, '[^0-9]', '', 'g') ~ '^234[0-9]{10}$'
  AND NOT EXISTS (
      SELECT 1 FROM identity.users x
      WHERE x.phone = '0' || right(regexp_replace(u.phone, '[^0-9]', '', 'g'), 10)
  );

UPDATE identity.users u
SET phone = '0' || regexp_replace(u.phone, '[^0-9]', '', 'g')
WHERE regexp_replace(u.phone, '[^0-9]', '', 'g') ~ '^[1-9][0-9]{9}$'
  AND NOT EXISTS (
      SELECT 1 FROM identity.users x
      WHERE x.phone = '0' || regexp_replace(u.phone, '[^0-9]', '', 'g')
  );

UPDATE identity.users u
SET phone = regexp_replace(u.phone, '[^0-9]', '', 'g')
WHERE u.phone <> regexp_replace(u.phone, '[^0-9]', '', 'g')
  AND NOT EXISTS (
      SELECT 1 FROM identity.users x
      WHERE x.phone = regexp_replace(u.phone, '[^0-9]', '', 'g')
  );

UPDATE identity.otp_codes
SET phone = '0' || right(regexp_replace(phone, '[^0-9]', '', 'g'), 10)
WHERE regexp_replace(phone, '[^0-9]', '', 'g') ~ '^234[0-9]{10}$';
