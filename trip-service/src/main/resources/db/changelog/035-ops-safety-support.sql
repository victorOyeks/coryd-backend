--liquibase formatted sql

--changeset coryd:100-trip-sos-ops-handling
-- Ops Console phase 5: an SOS is now worked like a case - acknowledged (claimed), escalated, resolved - and an unanswered
-- "Are you OK?" check joins the same queue as kind SAFETY_CHECK. contacts_notified is who the SOS texted (JSON), so the
-- console can show them. realert_level is how many "still unacknowledged" thresholds have fired, set atomically so each
-- fires once.
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS kind                    VARCHAR(20)  NOT NULL DEFAULT 'SOS';
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS safety_alert_id         VARCHAR(36);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS status                  VARCHAR(20)  NOT NULL DEFAULT 'OPEN';
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS contacts_notified       TEXT;
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS acknowledged_at         TIMESTAMP;
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS acknowledged_by         VARCHAR(36);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS acknowledged_by_name    VARCHAR(120);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS escalated_at            TIMESTAMP;
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS escalated_to            VARCHAR(30);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS escalation_contact      VARCHAR(200);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS escalation_contacted_at TIMESTAMP;
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS escalation_reference    VARCHAR(100);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS resolved_at             TIMESTAMP;
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS resolved_by             VARCHAR(36);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS resolved_by_name        VARCHAR(120);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS outcome                 VARCHAR(30);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS resolution_note         VARCHAR(1000);
ALTER TABLE trip.sos_incidents ADD COLUMN IF NOT EXISTS realert_level           INTEGER      NOT NULL DEFAULT 0;
-- Incidents from before ops could acknowledge anything stay OPEN for ops to close, but are never re-alerted.
UPDATE trip.sos_incidents SET realert_level = 99 WHERE realert_level = 0;
CREATE INDEX IF NOT EXISTS idx_sos_incidents_status ON trip.sos_incidents (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sos_incidents_route ON trip.sos_incidents (route_post_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sos_incidents_safety_alert ON trip.sos_incidents (safety_alert_id) WHERE safety_alert_id IS NOT NULL;

--changeset coryd:101-trip-sos-realerts
-- Each "still unacknowledged" re-alert sent to ops, for the incident's timeline. (incident, level) is unique: once per threshold.
CREATE TABLE IF NOT EXISTS trip.sos_realerts (
    id           VARCHAR(36) PRIMARY KEY,
    custom_id    VARCHAR(20) UNIQUE,
    incident_id  VARCHAR(36) NOT NULL,
    level        INTEGER     NOT NULL,
    minutes_open INTEGER     NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    updated_at   TIMESTAMP   NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sos_realerts_level ON trip.sos_realerts (incident_id, level);

--changeset coryd:102-trip-issue-report-ops-handling
-- Reports & disputes: assigned to an admin, resolved with an outcome and a message to the reporter, or dismissed.
-- IN_REVIEW was never written; IN_PROGRESS replaces it. reporter_role is the side of the trip the reporter was on.
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS reporter_role       VARCHAR(10);
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS assigned_to         VARCHAR(36);
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS assigned_to_name    VARCHAR(120);
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS assigned_at         TIMESTAMP;
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS outcome             VARCHAR(20);
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS message_to_reporter VARCHAR(1000);
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS action_type         VARCHAR(30);
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS action_reference    VARCHAR(100);
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS resolved_at         TIMESTAMP;
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS resolved_by         VARCHAR(36);
ALTER TABLE trip.issue_reports ADD COLUMN IF NOT EXISTS resolved_by_name    VARCHAR(120);
UPDATE trip.issue_reports SET status = 'IN_PROGRESS' WHERE status = 'IN_REVIEW';
UPDATE trip.issue_reports i
SET reporter_role = CASE WHEN b.rider_id = i.reported_by THEN 'RIDER' ELSE 'DRIVER' END
FROM trip.bookings b
WHERE b.id = i.booking_id AND i.reporter_role IS NULL;
CREATE INDEX IF NOT EXISTS idx_issue_reports_status ON trip.issue_reports (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_issue_reports_reported_by ON trip.issue_reports (reported_by);
CREATE INDEX IF NOT EXISTS idx_issue_reports_booking ON trip.issue_reports (booking_id);

--changeset coryd:103-trip-rating-moderation
-- A hidden rating stays on record but counts in no average and shows nowhere but the console.
ALTER TABLE trip.ratings ADD COLUMN IF NOT EXISTS hidden_at      TIMESTAMP;
ALTER TABLE trip.ratings ADD COLUMN IF NOT EXISTS hidden_by      VARCHAR(36);
ALTER TABLE trip.ratings ADD COLUMN IF NOT EXISTS hidden_by_name VARCHAR(120);
ALTER TABLE trip.ratings ADD COLUMN IF NOT EXISTS hidden_reason  VARCHAR(500);
CREATE INDEX IF NOT EXISTS idx_ratings_created ON trip.ratings (created_at DESC);

--changeset coryd:104-trip-ops-audit-log-any-target
-- The ops audit log now also records SOS, issue-report and rating actions, which aren't always on a trip.
ALTER TABLE trip.ops_audit_log ALTER COLUMN route_post_id DROP NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ops_audit_log_target ON trip.ops_audit_log (target_type, target_id, created_at);
