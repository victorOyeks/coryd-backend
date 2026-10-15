--liquibase formatted sql

--changeset coryd:041-user-blocks
CREATE TABLE IF NOT EXISTS trip.user_blocks
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    blocker_id VARCHAR(36) NOT NULL,
    blocked_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL,
    CONSTRAINT uq_user_blocks_pair UNIQUE (blocker_id, blocked_id)
);
CREATE INDEX IF NOT EXISTS idx_user_blocks_blocked ON trip.user_blocks (blocked_id);
