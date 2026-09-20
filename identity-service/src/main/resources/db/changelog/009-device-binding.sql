--liquibase formatted sql

--changeset coryd:025-identity-device-binding
-- Backs fingerprint sign-in, and the "where you're signed in" screen that has to come with it.
--
-- The fingerprint itself is never stored, sent or verified here - iOS and Android expose no API
-- for reading one, so there is nothing for a backend to receive. What biometrics actually do is
-- unlock the refresh token already held in the handset's Keychain/Keystore. These two changes are
-- the server's half of that: they make an unlocked token usable only on the device it was issued
-- to, and revocable from another one. Without them, putting a refresh token behind a fingerprint
-- would turn a 30-day bearer token into the sign-in credential with nothing tying it to a phone.

CREATE TABLE identity.user_devices
(
    id                VARCHAR(36) PRIMARY KEY,
    custom_id         VARCHAR(20) UNIQUE,
    user_id           VARCHAR(36)  NOT NULL REFERENCES identity.users (id),
    -- Client-generated and stable for the life of the install. A random UUID in the app's own
    -- storage: never the IMEI or advertising id, which are privileged and unnecessary here.
    device_id         VARCHAR(100) NOT NULL,
    device_name       VARCHAR(120),
    platform          VARCHAR(20),
    -- The user's choice, for display on the devices screen. NOT a permission: the backend cannot
    -- check a fingerprint, so this must never be read as proof that one was checked.
    biometric_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    last_seen_at      TIMESTAMP,
    -- Revoked rather than deleted, so a signed-out device cannot re-register and inherit the row.
    revoked           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uk_user_devices_user_device UNIQUE (user_id, device_id)
);

CREATE INDEX idx_user_devices_user ON identity.user_devices (user_id);

-- All four are nullable: sessions issued before this migration, and clients that never send a
-- device, keep working unbound. That is what lets this ship without signing everybody out.
-- Binding is enforced only once a token actually carries a device_id.
ALTER TABLE identity.refresh_tokens ADD COLUMN device_id VARCHAR(100);

-- Every token descended from one sign-in shares a family. Presenting an already-spent token
-- identifies the whole lineage to revoke, which is how a stolen token gets caught: the thief and
-- the real user cannot both keep rotating without one of them replaying a spent one.
ALTER TABLE identity.refresh_tokens ADD COLUMN family_id VARCHAR(36);

-- rotated_at separates an ordinary rotation from a deliberate revoke (both set revoked), and
-- replaced_by lets a client whose rotation response was lost be handed the same replacement again
-- instead of being signed out. On a mobile connection that dropped response is routine, and to a
-- user with biometrics on it reads as "my fingerprint stopped working".
ALTER TABLE identity.refresh_tokens ADD COLUMN rotated_at TIMESTAMP;
ALTER TABLE identity.refresh_tokens ADD COLUMN replaced_by VARCHAR(512);

CREATE INDEX idx_refresh_tokens_family ON identity.refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user_device ON identity.refresh_tokens (user_id, device_id);
