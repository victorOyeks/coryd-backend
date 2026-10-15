package org.coryd.profile.entity;

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
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.entity.BaseEntity;

import java.time.Instant;

/**
 * A rider's identity check - NIN plus a selfie - kept apart from RiderProfile because GET /riders/{id}
 * serves the profile to any signed-in user. This table keeps only the NIN's last four digits for
 * display; the full NIN exists only in Prembly's answer on verification_checks.raw_response. The
 * selfie is deleted once a decision is made.
 */
@Entity
@Table(name = "identity_verifications", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityVerification extends BaseEntity {

    @Column(nullable = false, unique = true, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private VerificationStatus status = VerificationStatus.PENDING;

    @Column(length = 4)
    private String ninLast4;

    private String selfieUrl;

    private String selfiePublicId;

    /** When the rider agreed to their NIN and selfie being checked with NIMC through Prembly. */
    private Instant consentAt;

    private Instant submittedAt;

    private Instant decidedAt;

    /** PREMBLY for an automatic match, otherwise the ops user. */
    @Column(length = 36)
    private String decidedBy;

    /** Why it was rejected - shown to the rider so they can fix it. */
    @Column(length = 500)
    private String rejectionReason;

    @Builder.Default
    private int attempts = 0;
}
