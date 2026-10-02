package org.coryd.trip.entity;

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
import org.coryd.common.enums.RouteStatus;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "route_posts", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePost extends BaseEntity {

    @Column(nullable = false)
    private String driverId;

    private String vehicleId;

    @Column(nullable = false)
    private String originLabel;
    @Column(nullable = false)
    private Double originLat;
    @Column(nullable = false)
    private Double originLng;

    @Column(nullable = false)
    private String destinationLabel;
    @Column(nullable = false)
    private Double destinationLat;
    @Column(nullable = false)
    private Double destinationLng;

    @Column(nullable = false)
    private Instant departureTime;

    @Column(nullable = false)
    private Integer seatCapacity;

    @Column(nullable = false)
    private Integer seatsAvailable;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal farePerSeat;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private RouteStatus status = RouteStatus.PUBLISHED;

    private Instant firstBookingLockedAt;

    /** Rider-facing hint about the path, e.g. "via Ketu bypass" - not shown to the driver only, riders see it in search results too. */
    @Column(length = 255)
    private String routeNote;

    /** When the driver actually tapped Start Trip / Complete Trip - departureTime is only the planned time, these are the real ones, used for the receipt's trip duration. */
    private Instant startedAt;
    private Instant completedAt;

    /** Set once RouteDepartureReminderJob has pushed the riders' "15 minutes to departure" reminder - never reset, guards against re-sending it on a later sweep. */
    private Instant reminderSentAt;

    /** The driver's own get-ready reminder, sent when the route enters the active window. */
    private Instant driverReminderSentAt;

    /** "Start it or cancel it" push once departure passes with the route still not started. */
    private Instant startNudgeSentAt;

    /** Google's driving time at posting - null on older routes and when Maps was unavailable. */
    private Integer estimatedDurationMinutes;
}
