--liquibase formatted sql

--changeset coryd:028-backfill-emergency-contacts
-- Riders who saved a contact through PUT /riders/me only had it on the profile, which SOS never read.
-- Copy each into emergency_contacts as that person's primary, unless they already keep a list there.
INSERT INTO profile.emergency_contacts (id, rider_id, name, phone, is_primary, created_at, updated_at)
SELECT md5(random()::text || clock_timestamp()::text || p.user_id)::uuid::text,
       p.user_id, LEFT(p.emergency_contact_name, 100), LEFT(p.emergency_contact_phone, 20), TRUE, NOW(), NOW()
FROM profile.rider_profiles p
WHERE COALESCE(TRIM(p.emergency_contact_name), '') <> ''
  AND COALESCE(TRIM(p.emergency_contact_phone), '') <> ''
  AND NOT EXISTS (SELECT 1 FROM profile.emergency_contacts c WHERE c.rider_id = p.user_id);
