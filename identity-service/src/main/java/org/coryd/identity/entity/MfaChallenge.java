package org.coryd.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.time.Instant;

/**
 * The step between an ops admin's password and their second factor: a single-use token (only its SHA-256 kept). LOGIN asks
 * for a code; ENROLL (two-factor required, not set up) sets it up. Five wrong codes and it's dead.
 */
@Entity
@Table(name = "mfa_challenges", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class MfaChallenge extends BaseEntity {

    public enum Purpose { LOGIN, ENROLL }

    @Column(nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Purpose purpose;

    @Column(nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    private Instant usedAt;
}
