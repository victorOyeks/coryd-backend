package org.coryd.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.DevicePlatform;

import java.time.Instant;

/**
 * One row per phone a user has signed in from - what "Sign out my lost device" and fingerprint
 * sign-in are both built on.
 *
 * <p>Biometric sign-in does not authenticate anyone against this table. The fingerprint never
 * leaves the handset: iOS and Android expose no API for reading it, so there is nothing to send
 * and nothing to verify server-side. What the fingerprint actually does is unlock the Keychain /
 * Keystore entry holding this device's refresh token. This table is the server's half of that
 * arrangement - it is what makes the unlocked token usable on <em>this</em> device only, and
 * revocable from another one.
 *
 * <p>{@code biometricEnabled} is therefore a record of the user's choice for the devices screen,
 * not a permission the backend enforces. Treat it as display state, never as proof that a
 * fingerprint was checked.
 */
@Entity
@Table(name = "user_devices", schema = "identity",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_id"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class UserDevice extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private String userId;

    /**
     * Client-generated, stable for the life of the install. Never the IMEI, advertising id or
     * anything else the OS treats as privileged - a random UUID kept in the app's own storage is
     * exactly right, and is all the binding needs.
     */
    @Column(name = "device_id", nullable = false, length = 100)
    private String deviceId;

    /** Shown to the user on the devices screen, e.g. "Victor's iPhone". Purely cosmetic. */
    @Column(length = 120)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DevicePlatform platform;

    /** The user's choice, for display. The real gate lives in the device's secure hardware. */
    @Builder.Default
    @Column(nullable = false)
    private boolean biometricEnabled = false;

    @Column
    private Instant lastSeenAt;

    /** Set by "sign out this device". Kept rather than deleted so a revoked device cannot silently re-register itself and inherit the row's history. */
    @Builder.Default
    @Column(nullable = false)
    private boolean revoked = false;
}
