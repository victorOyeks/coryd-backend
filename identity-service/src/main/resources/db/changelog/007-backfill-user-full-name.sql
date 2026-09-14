--liquibase formatted sql

--changeset coryd:023-identity-backfill-user-full-name
--preconditions onFail:MARK_RAN onError:MARK_RAN
--precondition-sql-check expectedResult:2 SELECT count(*) FROM information_schema.tables WHERE table_schema = 'profile' AND table_name IN ('rider_profiles', 'driver_profiles')
-- Signup only ever captured a phone, so full_name stayed null for every real account while the
-- name lived in profile-service. profile-service now pushes it on save; this backfills the
-- accounts created before that. Precondition skips it where profile-service has yet to migrate.

UPDATE identity.users u
SET full_name = p.full_name
FROM profile.rider_profiles p
WHERE p.user_id = u.id
  AND u.full_name IS NULL
  AND p.full_name IS NOT NULL;

UPDATE identity.users u
SET full_name = d.legal_name
FROM profile.driver_profiles d
WHERE d.user_id = u.id
  AND u.full_name IS NULL
  AND d.legal_name IS NOT NULL;
