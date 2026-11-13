--liquibase formatted sql

--changeset coryd:133-payment-job-runs
-- The last run of each scheduled job (common JobRunRecorder), for the Ops Console's system health screen.
CREATE TABLE IF NOT EXISTS payment.job_runs (
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
