--liquibase formatted sql

--changeset coryd:171-notification-onboarding-reminders
-- Onboarding reminders (docs/ops-console/onboarding-reminders.md): one row per person ever reminded. last_reminded_on is the
-- Lagos day of the last reminder - a person is claimed for a day by moving it forward in one statement, so a restart, a
-- second run the same day or a second instance never sends twice.
CREATE TABLE IF NOT EXISTS notification.onboarding_reminders (
    user_id           VARCHAR(36)  PRIMARY KEY,
    role              VARCHAR(10)  NOT NULL,
    last_step         VARCHAR(20)  NOT NULL,
    last_reason       VARCHAR(20)  NOT NULL,
    last_reminded_on  DATE         NOT NULL,
    last_reminded_at  TIMESTAMP    NOT NULL,
    reminder_count    INTEGER      NOT NULL DEFAULT 0,
    anchor_at         TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL
);

--changeset coryd:172-notification-daily-job-runs
-- The daily jobs (onboarding reminders, verification digest): one row per job per Lagos day. Claimed with an insert, so
-- whichever instance gets there first runs the day's job; a run that failed is retried (up to 3 tries), one stuck
-- RUNNING for 2 hours is taken over. recipients / items: what the run did.
CREATE TABLE IF NOT EXISTS notification.daily_job_runs (
    job_name     VARCHAR(40)  NOT NULL,
    run_date     DATE         NOT NULL,
    status       VARCHAR(10)  NOT NULL,
    attempts     INTEGER      NOT NULL DEFAULT 1,
    started_at   TIMESTAMP    NOT NULL,
    finished_at  TIMESTAMP,
    recipients   INTEGER,
    items        INTEGER,
    error        VARCHAR(500),
    updated_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (job_name, run_date)
);

--changeset coryd:173-notification-feed-step
-- The bell feed's onboarding reminders carry the step to open (and for which role), as the push does.
ALTER TABLE notification.user_notifications ADD COLUMN IF NOT EXISTS step VARCHAR(30);
ALTER TABLE notification.user_notifications ADD COLUMN IF NOT EXISTS role VARCHAR(10);

--changeset coryd:174-notification-ops-audit-feed-index
-- The unified audit feed pages ops_audit_log newest first by (created_at desc, id desc), as every other source.
CREATE INDEX IF NOT EXISTS idx_notification_ops_audit_feed ON notification.ops_audit_log (created_at DESC, id DESC);
