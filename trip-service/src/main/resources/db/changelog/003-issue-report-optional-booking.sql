--liquibase formatted sql

--changeset coryd:014-issue-reports-nullable-booking
-- Account-level "Report an issue" lets a rider report something with no trip selected.
ALTER TABLE trip.issue_reports ALTER COLUMN booking_id DROP NOT NULL;
