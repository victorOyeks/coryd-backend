--liquibase formatted sql

--changeset coryd:039-suspended-users
-- Mirror of identity-service's suspensions, so posting and booking can refuse a suspended account
-- even while its access token is still valid.
CREATE TABLE IF NOT EXISTS trip.suspended_users
(
    user_id      VARCHAR(36) PRIMARY KEY,
    reason       VARCHAR(500),
    suspended_at TIMESTAMP NOT NULL
);
