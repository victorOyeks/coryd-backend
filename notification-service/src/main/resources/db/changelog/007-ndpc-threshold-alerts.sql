--liquibase formatted sql

--changeset coryd:056-ndpc-threshold-alerts
-- NDPC registration tier emails already sent (approaching / reached, per tier), so each goes out once.
CREATE TABLE IF NOT EXISTS notification.ndpc_threshold_alerts
(
    id            VARCHAR(36) PRIMARY KEY,
    custom_id     VARCHAR(20) UNIQUE,
    threshold     BIGINT      NOT NULL,
    kind          VARCHAR(20) NOT NULL,
    data_subjects BIGINT      NOT NULL,
    created_at    TIMESTAMP   NOT NULL,
    updated_at    TIMESTAMP   NOT NULL,
    CONSTRAINT uq_ndpc_threshold_alerts UNIQUE (threshold, kind)
);
