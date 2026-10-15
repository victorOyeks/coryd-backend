--liquibase formatted sql

--changeset coryd:040-drop-system-ratings
-- New profiles no longer start with a 5-star rating from "SYSTEM" - it hid how real riders and
-- drivers rate someone. Averages are now only real ratings; no ratings yet reads as "New".
DELETE FROM trip.ratings WHERE rater_id = 'SYSTEM';
