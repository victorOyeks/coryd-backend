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
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "driver_documents", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDocument extends BaseEntity {

    @Column(nullable = false)
    private String driverId;

    private String vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DocumentType documentType;

    @Column(nullable = false)
    private String documentUrl;

    private String documentPublicId;

    private LocalDate expiresOn;

    @Column(name = "reminder_sent_14d")
    private boolean reminderSent14d;

    @Column(name = "reminder_sent_3d")
    private boolean reminderSent3d;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private VerificationStatus status = VerificationStatus.PENDING;

    private String rejectionReason;

    private String reviewedBy;

    private Instant reviewedAt;
}
