--liquibase formatted sql

--changeset coryd:120-identity-ops-admin-columns
-- Ops Console phase 6 - the admin team. An invited ops admin signs in with an email (no phone), so phone may be empty
-- for them; email is unique ignoring case. ops_role is the console role (SUPER_ADMIN, VERIFICATION, FINANCE, SUPPORT) of
-- an account holding OPS_ADMIN - empty counts as SUPER_ADMIN. ops_deactivated_at: a super admin removed their console
-- access (only the OPS_ADMIN sign-in is refused; their sessions were ended through tokens_valid_after). last_seen_at:
-- their last console sign-in, token refresh or console load.
ALTER TABLE identity.users ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS email VARCHAR(254);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS ops_role VARCHAR(20);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS invited_by VARCHAR(36);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS invited_by_name VARCHAR(150);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS invited_at TIMESTAMP;
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS ops_deactivated_at TIMESTAMP;
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS ops_deactivated_by VARCHAR(36);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS ops_deactivation_reason VARCHAR(500);
ALTER TABLE identity.users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower ON identity.users (LOWER(email)) WHERE email IS NOT NULL;

--changeset coryd:121-identity-existing-ops-admins-super
-- Everyone who already holds OPS_ADMIN keeps doing everything: SUPER_ADMIN. An admin created by hand with their email in
-- the phone column (the console's test admin, admin@coryd.africa) gets it as their email too, so email sign-in finds them.
UPDATE identity.users u SET ops_role = 'SUPER_ADMIN'
 WHERE u.ops_role IS NULL
   AND EXISTS (SELECT 1 FROM identity.user_roles r WHERE r.user_id = u.id AND r.role = 'OPS_ADMIN');
UPDATE identity.users u SET email = LOWER(TRIM(u.phone))
 WHERE u.email IS NULL AND u.phone LIKE '%@%'
   AND NOT EXISTS (SELECT 1 FROM identity.users o WHERE LOWER(o.email) = LOWER(TRIM(u.phone)));

--changeset coryd:122-identity-ops-invites
-- One-time set-password links for invited ops admins. Only the SHA-256 of the token is kept. A link works once, until
-- expires_at (72h), and only while it is the admin's newest invite (a resend or revoke ends the earlier one).
CREATE TABLE IF NOT EXISTS identity.ops_invites
(
    id              VARCHAR(36) PRIMARY KEY,
    custom_id       VARCHAR(20) UNIQUE,
    user_id         VARCHAR(36) NOT NULL,
    token_hash      VARCHAR(64) NOT NULL UNIQUE,
    expires_at      TIMESTAMP   NOT NULL,
    used_at         TIMESTAMP,
    revoked_at      TIMESTAMP,
    created_by      VARCHAR(36),
    created_by_name VARCHAR(150),
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ops_invites_user ON identity.ops_invites (user_id, created_at);

--changeset coryd:123-identity-account-actions-audit-order
-- The unified audit log reads account actions newest first across all accounts.
CREATE INDEX IF NOT EXISTS idx_account_actions_created ON identity.account_actions (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_account_actions_actor ON identity.account_actions (actor_id, created_at);
