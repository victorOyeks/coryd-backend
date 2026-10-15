package org.coryd.trip.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bookings", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Booking extends BaseEntity {

    @Column(nullable = false)
    private String routePostId;

    @Column(nullable = false)
    private String riderId;

    @Column(nullable = false)
    private Integer seatCount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fareTotal;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.HELD;

    @Column(nullable = false)
    private Instant holdExpiresAt;

    private Instant pickupConfirmedAt;

    /** Set when payment confirms the booking - null means it never became a real booking. */
    private Instant confirmedAt;

    /** Only the rider ever sees this, through BookingDetailsResponse - never serialised with the entity. */
    @JsonIgnore
    @Column(length = 4)
    private String pickupCode;

    @JsonIgnore
    @Builder.Default
    @Column(nullable = false)
    private int pickupCodeAttempts = 0;

    // Snapshotted at booking time so history survives later profile/route edits.
    private String driverNameSnapshot;
    private String vehiclePlateSnapshot;
    private String routeSummarySnapshot;

    @Column(precision = 10, scale = 2)
    private BigDecimal farePerSeatSnapshot;

    private String pickupLabel;
    private Double pickupLat;
    private Double pickupLng;

    /** Where the rider gets out, when they join part-way - null means the route's end. */
    private String dropoffLabel;
    private Double dropoffLat;
    private Double dropoffLng;
}
