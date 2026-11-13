package org.coryd.trip.dto;

import java.math.BigDecimal;

/**
 * A rider's numbers for the Riders Directory. reliabilityPercent: trips completed out of those completed, cancelled by the
 * rider after paying, or missed (no-show) - null until there's one of those. totalSpent: the fares of completed trips.
 * lastTripAt: when their latest completed trip ended - null with no completed trip.
 */
public record OpsRiderStats(String riderId, long trips, Double ratingAvg, long ratingCount, Integer reliabilityPercent,
                            long reportsFiled, BigDecimal totalSpent, java.time.Instant lastTripAt) {
}
