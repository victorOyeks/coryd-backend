package org.coryd.identity.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.HeardAboutUs;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    /** Null only for an ops admin invited by email (they sign in with the email). */
    @Column(unique = true, length = 20)
    private String phone;

    /** Ops admins only: the console sign-in, stored lower case, unique. Null for riders and drivers. */
    @Column(length = 254)
    private String email;

    @Column(length = 100)
    private String firstName;

    @Column(length = 100)
    private String lastName;

    /** Set the moment verify-otp succeeds - lets onboarding-status distinguish "never verified" from later incomplete steps. */
    private Instant phoneVerifiedAt;

    /** Null until "Set up your password" completes - a phone-verified account isn't fully onboarded yet. */
    private String passwordHash;

    /** R-104: explicit consent timestamp, not implied by continuing. Null until accepted. */
    private Instant termsAcceptedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", schema = "identity", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    @Builder.Default
    private Set<UserRole> roles = new HashSet<>();

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /** Set while ops has the account suspended - no tokens are issued to it. */
    private Instant suspendedAt;

    @Column(length = 500)
    private String suspensionReason;

    /**
     * Access tokens issued before this are refused by every service (suspension, sign out everywhere). Only ever moves
     * forward - a reinstate doesn't revive old tokens. Null when nothing was ever revoked.
     */
    private Instant tokensValidAfter;

    /** The console role of an OPS_ADMIN account; null counts as SUPER_ADMIN (OpsRole.effective). */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OpsRole opsRole;

    /** The super admin who invited this ops admin, and when - null for admins created before invites. */
    @Column(length = 36)
    private String invitedBy;

    @Column(length = 150)
    private String invitedByName;

    private Instant invitedAt;

    /** Set while a super admin has this admin's console access removed: OPS_ADMIN sign-in refused, sessions ended. */
    private Instant opsDeactivatedAt;

    @Column(length = 36)
    private String opsDeactivatedBy;

    @Column(length = 500)
    private String opsDeactivationReason;

    /** Last console sign-in, token refresh or console load (ops admins only). */
    private Instant lastSeenAt;

    /** Ops admins' two-factor sign-in: the confirmed TOTP secret, AES-GCM encrypted (MfaSecretCipher). Never logged. */
    @Column(length = 255)
    private String mfaSecret;

    /** Set once the admin confirmed a code from their app - only then does console sign-in ask for one. */
    private Instant mfaEnabledAt;

    /** A secret shown by setup and not yet confirmed (encrypted), and when it was created. */
    @Column(length = 255)
    private String mfaPendingSecret;

    private Instant mfaPendingAt;

    /** The 30-second step of the last code accepted - a code is good once. */
    private Long mfaLastStep;

    /** "How did you hear about us?" from signup - first answer wins. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private HeardAboutUs heardAboutUs;

    /** What they wrote when heardAboutUs is OTHER. */
    @Column(length = 100)
    private String heardAboutUsOther;
}
