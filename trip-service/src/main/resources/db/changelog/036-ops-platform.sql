--liquibase formatted sql

--changeset coryd:130-trip-platform-settings
-- Ops Console phase 6: business rules ops can change at runtime (docs/ops-console/settings.md). One row per setting ops
-- has ever set - a setting with no row (or a null value) uses each service's own property. A scheduled change waits in
-- scheduled_value/scheduled_from until PlatformSettingsService applies it. version only goes up, so consumers can tell
-- the newer of two records apart.
CREATE TABLE IF NOT EXISTS trip.platform_settings (
    setting_key          VARCHAR(36)  PRIMARY KEY,
    value                VARCHAR(200),
    scheduled_value      VARCHAR(200),
    scheduled_from       TIMESTAMP,
    scheduled_change_id  VARCHAR(36),
    version              BIGINT       NOT NULL DEFAULT 0,
    updated_by           VARCHAR(36),
    updated_by_name      VARCHAR(120),
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_platform_settings_scheduled ON trip.platform_settings (scheduled_from) WHERE scheduled_from IS NOT NULL;

-- Every change to a setting, newest last: set now, scheduled, applied when its time came, schedule cancelled, reset.
CREATE TABLE IF NOT EXISTS trip.platform_setting_changes (
    id              VARCHAR(36)  PRIMARY KEY,
    custom_id       VARCHAR(20)  UNIQUE,
    setting_key     VARCHAR(36)  NOT NULL,
    action          VARCHAR(20)  NOT NULL,
    old_value       VARCHAR(200),
    new_value       VARCHAR(200),
    effective_from  TIMESTAMP,
    actor_id        VARCHAR(36)  NOT NULL,
    actor_name      VARCHAR(120),
    reason          VARCHAR(500) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_platform_setting_changes_key ON trip.platform_setting_changes (setting_key, created_at);

--changeset coryd:131-trip-job-runs
-- The last run of each scheduled job (common JobRunRecorder), for the console's system health screen.
CREATE TABLE IF NOT EXISTS trip.job_runs (
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

--changeset coryd:132-trip-dashboard-indexes
-- The dashboard and reports filter bookings and audit rows by time.
CREATE INDEX IF NOT EXISTS idx_bookings_created_at ON trip.bookings (created_at);
-- trip.ops_audit_log's time index comes from coryd:125 (created_at DESC, id DESC), which also serves these date ranges.
