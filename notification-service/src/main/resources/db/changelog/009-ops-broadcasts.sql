--liquibase formatted sql

--changeset coryd:135-notification-broadcasts
-- Ops Console phase 6: messages ops sends to an audience (docs/ops-console/broadcasts.md). recipients are resolved when
-- sending starts (audience_resolved_at), then sent at a capped rate, each with its own outcome per channel.
CREATE TABLE IF NOT EXISTS notification.broadcasts (
    id                        VARCHAR(36)   PRIMARY KEY,
    custom_id                 VARCHAR(20)   UNIQUE,
    title                     VARCHAR(80)   NOT NULL,
    body                      VARCHAR(1000) NOT NULL,
    channels                  VARCHAR(60)   NOT NULL,
    audience_type             VARCHAR(20)   NOT NULL,
    audience_active_days      INTEGER,
    audience_ids              TEXT,
    audience_refs             TEXT,
    urgent                    BOOLEAN       NOT NULL DEFAULT FALSE,
    status                    VARCHAR(20)   NOT NULL,
    requested_for             TIMESTAMP     NOT NULL,
    scheduled_for             TIMESTAMP     NOT NULL,
    deferred_for_quiet_hours  BOOLEAN       NOT NULL DEFAULT FALSE,
    reason                    VARCHAR(500)  NOT NULL,
    created_by                VARCHAR(36)   NOT NULL,
    created_by_name           VARCHAR(120),
    cancelled_by              VARCHAR(36),
    cancelled_by_name         VARCHAR(120),
    cancel_reason             VARCHAR(500),
    cancelled_at              TIMESTAMP,
    audience_resolved_at      TIMESTAMP,
    started_at                TIMESTAMP,
    finished_at               TIMESTAMP,
    recipients_total          INTEGER       NOT NULL DEFAULT 0,
    recipients_sent           INTEGER       NOT NULL DEFAULT 0,
    recipients_failed         INTEGER       NOT NULL DEFAULT 0,
    recipients_skipped        INTEGER       NOT NULL DEFAULT 0,
    last_error                VARCHAR(500),
    created_at                TIMESTAMP     NOT NULL,
    updated_at                TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_broadcasts_due ON notification.broadcasts (status, scheduled_for);
CREATE INDEX IF NOT EXISTS idx_broadcasts_created ON notification.broadcasts (created_at);

CREATE TABLE IF NOT EXISTS notification.broadcast_recipients (
    id             VARCHAR(36)  PRIMARY KEY,
    custom_id      VARCHAR(20)  UNIQUE,
    broadcast_id   VARCHAR(36)  NOT NULL,
    user_id        VARCHAR(36)  NOT NULL,
    role           VARCHAR(10),
    ref            VARCHAR(12),
    phone          VARCHAR(30),
    email          VARCHAR(255),
    status         VARCHAR(10)  NOT NULL,
    skip_reason    VARCHAR(120),
    push           VARCHAR(15),
    sms            VARCHAR(15),
    email_outcome  VARCHAR(15),
    whatsapp       VARCHAR(15),
    error          VARCHAR(300),
    sent_at        TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uq_broadcast_recipient UNIQUE (broadcast_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_broadcast_recipients_pending ON notification.broadcast_recipients (broadcast_id, status);

--changeset coryd:136-notification-ops-audit-log
-- Ops actions notification-service carries out (broadcasts composed, cancelled; reports sent on demand) - the same
-- who / what / before / after / why shape as trip.ops_audit_log and payment.ops_money_audit, for the unified audit feed.
CREATE TABLE IF NOT EXISTS notification.ops_audit_log (
    id            VARCHAR(36)  PRIMARY KEY,
    custom_id     VARCHAR(20)  UNIQUE,
    actor_id      VARCHAR(36)  NOT NULL,
    actor_name    VARCHAR(120),
    action        VARCHAR(40)  NOT NULL,
    target_type   VARCHAR(20)  NOT NULL,
    target_id     VARCHAR(36)  NOT NULL,
    before_state  TEXT,
    after_state   TEXT,
    reason        VARCHAR(500) NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notification_ops_audit_target ON notification.ops_audit_log (target_type, target_id, created_at);
CREATE INDEX IF NOT EXISTS idx_notification_ops_audit_created ON notification.ops_audit_log (created_at);

--changeset coryd:137-notification-job-runs
CREATE TABLE IF NOT EXISTS notification.job_runs (
    job_name          VARCHAR(120) PRIMARY KEY,
    last_started_at   TIMESTAMP,
    last_finished_at  TIMESTAMP,
    last_duration_ms  BIGINT       NOT NULL DEFAULT 0,
    last_status       VARCHAR(10)  NOT NULL,
    last_error        VARCHAR(500),
    last_success_at   TIMESTAMP,
    last_failure_at   TIMESTAMP,
    runs              BIGINT       NOT NULL DEFAULT 0,
    failures          BIGINT       NOT NULL DEFAULT 0,
    updated_at        TIMESTAMP    NOT NULL
);

--changeset coryd:138-notification-log-created-index
-- Integration health counts notification_log rows by time.
CREATE INDEX IF NOT EXISTS idx_notification_log_created ON notification.notification_log (created_at);
