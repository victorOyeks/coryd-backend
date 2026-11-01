package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A rider's trip numbers as they now stand - sent by trip-service when one of their bookings changes, a driver rates
 * them or reports a problem on their trip, and nightly for every rider. Whole values, never deltas, so a repeat is
 * harmless. computedAt lets a consumer ignore one older than what it already has.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderStatsChangedEvent {
    private String riderId;
    private long trips;
    private Double ratingAvg;
    private long ratingCount;
    private Integer reliabilityPercent;
    private long reportsFiled;
    private BigDecimal totalSpent;
    private Instant computedAt;
}
