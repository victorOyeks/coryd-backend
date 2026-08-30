--liquibase formatted sql

--changeset coryd:017-saved-places-user-id
-- Was rider-only - the driver app's Saved Places screen needs the same feature, and a saved
-- place isn't actually rider-specific.
ALTER TABLE trip.saved_places RENAME COLUMN rider_id TO user_id;
ALTER INDEX trip.idx_saved_places_rider_id RENAME TO idx_saved_places_user_id;
