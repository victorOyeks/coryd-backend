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
    /** When the most recent completed ride on their trips ended - null when there's none (or from a sender that predates it). */
    private Instant lastTripAt;
    private Instant computedAt;

    /** The constructor from before lastTripAt, kept so existing callers still compile; lastTripAt is null. */
    public DriverStatsChangedEvent(String driverId, long trips, Double ratingAvg, long ratingCount, Integer reliabilityPercent,
                                   long reportsFiled, Instant computedAt) {
        this(driverId, trips, ratingAvg, ratingCount, reliabilityPercent, reportsFiled, null, computedAt);
    }
}
