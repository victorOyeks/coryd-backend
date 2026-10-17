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
 * A person's identity check - NIN plus a live selfie, run by Smile ID - kept apart from the profiles
 * because GET /riders/{id} serves a profile to any signed-in user. This table keeps only the NIN's
 * last four digits for display; the full NIN exists only in Smile ID's answer on
 * verification_checks.raw_response. The selfie stays with Smile ID (selfieUrl is only set by the
 * older upload-a-selfie submission, and is deleted once a decision is made).
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

    /** When they agreed to their NIN and selfie being checked with NIMC through Smile ID. */
    private Instant consentAt;

    private Instant submittedAt;

    private Instant decidedAt;

    /** SMILE_ID for an automatic match, otherwise the ops user. */
    @Column(length = 36)
    private String decidedBy;

    /** Why it was rejected - shown to the rider so they can fix it. */
    @Column(length = 500)
    private String rejectionReason;

    @Builder.Default
    private int attempts = 0;
}
