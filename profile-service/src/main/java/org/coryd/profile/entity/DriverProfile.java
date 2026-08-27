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

    @Column(length = 100)
    private String legalName;

    private String photoUrl;

    private String email;

    private LocalDate dateOfBirth;

    private String governmentIdUrl;

    private String idNumber;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private VerificationStatus governmentIdStatus = VerificationStatus.PENDING;

    private String governmentIdRejectionReason;

    private String selfieUrl;

    private String selfiePublicId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private VerificationStatus selfieStatus = VerificationStatus.PENDING;

    private String selfieRejectionReason;

    /**
     * When the driver last sent a selfie in. Kept separately from selfieUrl because approving a
     * selfie deletes the image and nulls the URL - the submission still happened, and the
     * onboarding checklist has to keep saying so.
     */
    private Instant selfieSubmittedAt;
}
