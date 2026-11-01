package org.coryd.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.coryd.common.enums.DriverDirectoryStatus;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.enums.VerificationStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per driver with everything the Ops Console filters, sorts and searches on - so the Drivers Directory and the
 * Verification Queue are a single indexed query each, however many drivers there are. Rebuilt from this service's own
 * records whenever they change (DriverIndexListener); trip, payment and identity numbers arrive as events and are kept
 * as they were sent. Never the source of truth - DriverIndexService can rebuild any row.
 */
@Entity
@Table(name = "driver_index", schema = "profile")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverIndex {

    @Id
    @Column(length = 36)
    private String driverId;

    /** The id without dashes, lower case - the "D-4F2A91" reference searches and sorts on this. */
    @Column(nullable = false, length = 36)
    private String refKey;

    private String firstName;
    private String lastName;

    /** "first last", lower case - name search and sort. */
    @Column(length = 255)
    private String nameKey;

    private String photoUrl;

    @Column(length = 30)
    private String phone;

    /** Just the digits of the phone, for search. */
    @Column(length = 30)
    private String phoneDigits;

    /** Sign-up date from identity-service, or when the driver profile was made until that's known. */
    private Instant joinedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VerificationStatus overallStatus;

    @Builder.Default
    @Column(nullable = false)
    private boolean resubmitted = false;

    private Instant suspendedAt;

    @Column(length = 500)
    private String suspensionReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DriverDirectoryStatus directoryStatus;

    @Column(length = 50)
    private String vehicleMake;
    @Column(length = 50)
    private String vehicleModel;
    private Integer vehicleYear;
    @Column(length = 30)
    private String vehicleColor;
    @Column(length = 20)
    private String plateNumber;

    /** Plate without spaces or dashes, lower case - search. */
    @Column(length = 20)
    private String plateKey;

    /** "make model", lower case - the Vehicle column's sort. */
    @Column(length = 110)
    private String vehicleKey;

    // From trip-service, as last sent.
    private Long trips;
    private Double ratingAvg;
    private Long ratingCount;
    private Integer reliabilityPercent;
    private Long reportsFiled;
    private Instant statsAt;

    // From payment-service, as last sent.
    @Column(precision = 14, scale = 2)
    private BigDecimal earningsNet;
    @Column(precision = 14, scale = 2)
    private BigDecimal earningsGross;
    private Instant earningsAt;

    // The Verification Queue.
    /** At least one item is waiting for ops. */
    @Builder.Default
    @Column(nullable = false)
    private boolean inReview = false;

    /** Set while the licence is out with Smile ID - it joins the queue once it has waited long enough. */
    private Instant licenceCheckStartedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VerificationQueueTab queueTab;

    /** When the oldest still-waiting item came in. */
    private Instant waitingSince;

    /** The earliest submission among the items. */
    private Instant submittedAt;

    private Instant refreshedAt;
}
