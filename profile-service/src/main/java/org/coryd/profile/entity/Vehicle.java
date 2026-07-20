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


@Entity
@Table(name = "vehicles", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle extends BaseEntity {

    @Column(nullable = false)
    private String driverId;

    @Column(length = 50)
    private String make;

    @Column(length = 50)
    private String model;

    private Integer year;

    @Column(nullable = false, unique = true, length = 20)
    private String plateNumber;

    @Column(nullable = false)
    private Integer seatCapacity;

    private String exteriorPhotoUrl;
    private String interiorPhotoUrl;
    private String acPhotoUrl;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    private String rejectionReason;
}
