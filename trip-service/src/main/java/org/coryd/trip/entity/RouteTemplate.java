package org.coryd.trip.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A driver's regular trip - "weekdays at 07:00, Ikorodu to VI" - set once. RouteTemplateService posts a
 * real RoutePost for each day it runs, as far ahead as a driver may post by hand (trip.max-days-ahead).
 */
@Entity
@Table(name = "route_templates", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RouteTemplate extends BaseEntity {

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

    /** Seats offered on each trip. */
    @Column(nullable = false)
    private Integer typicalSeatCount;

    /** Mon=1 ... Sun=64, as GeoMath.dayBit. */
    private Integer daysMask;

    /** Departure, minutes after midnight Lagos time. */
    private Integer departureMinutes;

    private BigDecimal farePerSeat;

    private String routeNote;

    /** The road the driver picked for every trip; null = Google's usual road at posting time. */
    @Column(columnDefinition = "TEXT")
    private String routePolyline;

    @Column(length = 255)
    private String viaSummary;

    /** Last day it runs; null = until the driver stops it. */
    private LocalDate activeUntil;

    @Builder.Default
    @Column(nullable = false)
    private boolean paused = false;

    /** Seats held on every trip for favourite riders, who each get the usual 15 minutes to accept. */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "route_template_seats", schema = "trip", joinColumns = @JoinColumn(name = "route_template_id"))
    private List<StandingSeat> standingSeats = new ArrayList<>();

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StandingSeat {
        @Column(name = "rider_id", nullable = false, length = 36)
        private String riderId;
        @Column(name = "seat_count", nullable = false)
        private Integer seatCount;
    }
}
