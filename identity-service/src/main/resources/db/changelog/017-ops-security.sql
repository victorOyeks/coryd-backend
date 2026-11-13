--liquibase formatted sql

--changeset coryd:140-identity-ops-mfa-columns
-- Ops console phase 7 - two-factor sign-in (TOTP, RFC 6238) for ops admins. mfa_secret: the confirmed authenticator
-- secret, AES-GCM encrypted (never stored or logged in clear). mfa_enabled_at: set once the admin confirmed a code - only
-- then does sign-in ask for one. mfa_pending_secret/at: a secret shown by setup and not yet confirmed. mfa_last_step: the
-- 30-second step of the last code accepted, so the same code can't be used twice.
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS mfa_secret VARCHAR(255);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS mfa_enabled_at TIMESTAMP;
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS mfa_pending_secret VARCHAR(255);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS mfa_pending_at TIMESTAMP;
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS mfa_last_step BIGINT;

--changeset coryd:141-identity-mfa-recovery-codes
-- One-time recovery codes (10 per admin, shown once). Only the SHA-256 of each code is kept; used_at marks a spent code.
CREATE TABLE IF NOT EXISTS identity.mfa_recovery_codes
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    user_id    VARCHAR(36) NOT NULL,
    code_hash  VARCHAR(64) NOT NULL,
    used_at    TIMESTAMP,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mfa_recovery_codes_user ON identity.mfa_recovery_codes (user_id);

--changeset coryd:142-identity-mfa-challenges
-- The step between password and second factor: a single-use token (only its SHA-256 kept) for LOGIN (enter a code, 5
-- minutes) or ENROLL (two-factor is required and not set up yet). attempts counts wrong codes - 5 and it's dead.
CREATE TABLE IF NOT EXISTS identity.mfa_challenges
(
    id         VARCHAR(36) PRIMARY KEY,
    custom_id  VARCHAR(20) UNIQUE,
    user_id    VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    purpose    VARCHAR(10) NOT NULL,
    expires_at TIMESTAMP   NOT NULL,
    attempts   INTEGER     NOT NULL DEFAULT 0,
    used_at    TIMESTAMP,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mfa_challenges_user ON identity.mfa_challenges (user_id);

--changeset coryd:143-identity-refresh-token-session-start
-- Ops console session limits: when the sign-in that started this refresh-token lineage happened, carried through every
-- rotation, so the absolute limit (12h by default) can be enforced. Null for rider/driver tokens and older ops tokens.
ALTER TABLE identity.refresh_tokens ADD COLUMN IF NOT EXISTS session_started_at TIMESTAMP;
