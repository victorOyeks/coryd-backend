package org.coryd.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.time.Instant;

/** A one-time set-password link for an invited ops admin (coryd:122). Only the token's SHA-256 is stored. */
@Entity
@Table(name = "ops_invites", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OpsInvite extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    /** Withdrawn, or replaced by a newer invite. */
    private Instant revokedAt;

    @Column(length = 36)
    private String createdBy;

    @Column(length = 150)
    private String createdByName;

    public boolean isPending(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}
