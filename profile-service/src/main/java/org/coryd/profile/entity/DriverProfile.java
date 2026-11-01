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
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;


@Entity
@Table(name = "driver_profiles", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DriverProfile extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String userId;

    /** Split so a verification provider can match the surname on its own; must match the driver's licence. */
    @Column(length = 100)
    private String firstName;

    @Column(length = 100)
    private String lastName;

    private String photoUrl;

    private String email;

    private LocalDate dateOfBirth;

    private String governmentIdUrl;

    private String governmentIdPublicId;

    private String idNumber;

    /**
     * The driver's NIN lookup (number only - see SmileIdProperties.driversUseNinLookup): PENDING until NIMC
     * confirms a NIN in their name (APPROVED); IN_REVIEW when ops has to look; REJECTED when ops turned it down.
     * The driver's identity is verified once this and their licence are both approved.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private VerificationStatus ninStatus = VerificationStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private VerificationStatus governmentIdStatus = VerificationStatus.PENDING;

    private String governmentIdRejectionReason;

    /** When the licence last entered review - the verification queue's "submitted". */
    private Instant governmentIdSubmittedAt;

    /** It's back in review after ops rejected it. Cleared once decided again. */
    @Builder.Default
    @Column(nullable = false)
    private boolean governmentIdResubmitted = false;

    /** Ops decided the licence while Smile ID's result was still out - when it comes, it overrides that decision. */
    @Builder.Default
    @Column(nullable = false)
    private boolean governmentIdDecidedDuringCheck = false;

    /** Set once, the first time this driver ever reaches overallStatus APPROVED - never moves
     * after that, even if a later rejection + re-approval cycle happens. "Verified Since" on the
     * Verification Status screen. */
    private Instant verifiedSince;

}
