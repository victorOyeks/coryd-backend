--liquibase formatted sql

--changeset coryd:080-identity-tokens-valid-after
-- Every service refuses an access token issued before this (common JwtFilter, fed by the compacted
-- coryd-user-tokens-revoked topic and GET /internal/users/token-revocations). Set when ops suspends the account or signs
-- it out everywhere, and when the account is deleted; never moved back - reinstating doesn't revive old tokens.
-- Accounts suspended before this existed are cut off from their suspension time.
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS tokens_valid_after TIMESTAMP;
UPDATE identity.users SET tokens_valid_after = suspended_at
 WHERE active = FALSE AND suspended_at IS NOT NULL AND tokens_valid_after IS NULL;
CREATE INDEX IF NOT EXISTS idx_users_tokens_valid_after ON identity.users (tokens_valid_after)
    WHERE tokens_valid_after IS NOT NULL OR active = FALSE;

--changeset coryd:081-identity-account-actions
-- Every ops action on an account, never edited: suspend, reinstate, sign out everywhere, name/email and vehicle edits,
-- tries given back. actor_* is the ops admin (name as on their token); changes is a JSON array of
-- {field, before, after}. Suspension history is read from here, so it survives a reinstate.
CREATE TABLE IF NOT EXISTS identity.account_actions
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    user_id    VARCHAR(36) NOT NULL,
    action     VARCHAR(30) NOT NULL,
    actor_id   VARCHAR(36),
    actor_name VARCHAR(150),
    reason     VARCHAR(500),
    changes    TEXT,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_account_actions_user ON identity.account_actions (user_id, created_at);
-- Accounts suspended before this table existed: their suspension, so it shows in the history (who did it wasn't kept).
INSERT INTO identity.account_actions (id, user_id, action, reason, created_at, updated_at)
SELECT gen_random_uuid()::text, u.id, 'SUSPENDED', u.suspension_reason, u.suspended_at, u.suspended_at
  FROM identity.users u
 WHERE u.active = FALSE AND u.suspended_at IS NOT NULL;

--changeset coryd:082-identity-account-deletion-requests
-- Account deletions for the ops console (read-only). PENDING: the last try was refused (a trip under way, money still
-- moving) - blockers says why. COMPLETED: the account is gone. Only what's needed to recognise the request is kept once
-- the account is deleted: the user id, the roles it held and the phone's last four digits.
CREATE TABLE IF NOT EXISTS identity.account_deletion_requests
(
    id               VARCHAR(36) PRIMARY KEY,
    custom_id        VARCHAR(20) UNIQUE,
    user_id          VARCHAR(36) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    requested_by     VARCHAR(36) NOT NULL,
    roles            VARCHAR(100),
    phone_last4      VARCHAR(4),
    joined_at        TIMESTAMP,
    blockers         TEXT,
    attempts         INT         NOT NULL DEFAULT 1,
    last_attempt_at  TIMESTAMP   NOT NULL,
    completed_at     TIMESTAMP,
    completed_by     VARCHAR(36),
    created_at       TIMESTAMP   NOT NULL,
    updated_at       TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_account_deletion_requests_status ON identity.account_deletion_requests (status, last_attempt_at);
CREATE INDEX IF NOT EXISTS idx_account_deletion_requests_user ON identity.account_deletion_requests (user_id);
