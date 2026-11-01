package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A driver's trip numbers as they now stand - sent by trip-service when a trip completes, a driver cancels, a rider
 * rates them or reports a problem, and nightly for every driver. Whole values, never deltas, so a repeat is harmless.
 * computedAt lets a consumer ignore one older than what it already has.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverStatsChangedEvent {
    private String driverId;
    private long trips;
    private Double ratingAvg;
    private long ratingCount;
    private Integer reliabilityPercent;
    private long reportsFiled;
    private Instant computedAt;
}
