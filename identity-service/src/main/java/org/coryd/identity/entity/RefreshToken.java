package org.coryd.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.UserRole;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken extends BaseEntity {

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    /** Which role context this session was issued for, so refresh preserves it instead of silently switching apps. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole activeRole;

    @Column(nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean revoked = false;

    /**
     * The device this token may be presented from. Null on tokens issued before device binding
     * existed, and on clients that still do not send one - those keep working unbound, which is
     * what lets this ship without logging everybody out.
     *
     * <p>Binding matters far more now than it used to. A refresh token behind a fingerprint is no
     * longer just a session: it is the sign-in credential, valid for thirty days. Unbound, a copy
     * lifted off one handset works from anywhere in the world.
     */
    @Column(length = 100)
    private String deviceId;

    /**
     * Every token descended from one sign-in shares a family id. Rotation issues a new token in
     * the same family, so presenting an already-spent token identifies the whole lineage to
     * revoke - see AuthService.refresh().
     */
    @Column(length = 36)
    private String familyId;

    /** When this token was spent by a rotation. Distinguishes an ordinary rotation from a deliberate revoke, both of which set {@code revoked}. */
    @Column
    private Instant rotatedAt;

    /**
     * The token handed out in exchange for this one. Kept so a client that never received the
     * rotation response - a dropped connection at the wrong moment - can be given the same
     * replacement again instead of being signed out.
     */
    @Column(length = 512)
    private String replacedBy;

    /**
     * Ops console sessions: when the sign-in that started this lineage happened, carried through every rotation - the
     * absolute session limit counts from here. Null for rider/driver sessions.
     */
    private Instant sessionStartedAt;
}
