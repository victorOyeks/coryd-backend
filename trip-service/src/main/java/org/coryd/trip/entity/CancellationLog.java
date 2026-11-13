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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One cancellation for the Ops Console's Cancellations Log - a ROUTE row when a whole trip is called off, a BOOKING row
 * for each paid seat that ends (a route's own seats point back at it through parentId). Written in the same transaction
 * as the cancellation itself. Unpaid holds that lapse or are let go aren't cancellations and never appear here.
 */
@Entity
@Table(name = "cancellation_log", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CancellationLog extends BaseEntity {

    public enum Kind { ROUTE, BOOKING }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(nullable = false, length = 36)
    private String routePostId;

    @Column(length = 36)
    private String bookingId;

    /** The ROUTE row this seat was cancelled with - null for a seat cancelled on its own. */
    @Column(length = 36)
    private String parentId;

    @Column(length = 36)
    private String riderId;

    @Column(length = 36)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CancellationSource source;

    /** The raw cancelledBy carried on the event - what payment-service and notification-service saw. */
    @Column(length = 20)
    private String cancelledBy;

    /** Whoever did it: the rider, the driver, or the ops admin - null for the system. */
    @Column(length = 36)
    private String actorId;

    @Column(length = 120)
    private String actorName;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private CancellationFault fault;

    @Column(nullable = false)
    private Instant cancelledAt;

    @Column(nullable = false)
    private Instant departureTime;

    /** Negative once departure had passed. */
    @Column(nullable = false)
    private int minutesBeforeDeparture;

    @Column(length = 20)
    private String routeStatusBefore;

    @Column(length = 20)
    private String bookingStatusBefore;

    private Integer seatCount;

    @Column(precision = 10, scale = 2)
    private BigDecimal fareTotal;

    @Builder.Default
    @Column(nullable = false)
    private boolean paid = false;

    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    /** What the rider should get back: the fare less any fee kept - 0 when nothing was paid. */
    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal refundExpected = BigDecimal.ZERO;

    /** ROUTE rows: how many paid seats the cancellation ended. */
    @Builder.Default
    @Column(nullable = false)
    private int bookingsAffected = 0;

    private String originLabel;
    private String destinationLabel;

    /** Rebuilt from older data when the log was introduced - source, fault and fee are best guesses, no reason. */
    @Builder.Default
    @Column(nullable = false)
    private boolean backfilled = false;
}
