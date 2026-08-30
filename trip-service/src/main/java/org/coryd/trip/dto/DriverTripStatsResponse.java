package org.coryd.trip.dto;

/** Dashboard's stat tiles. reliabilityPercent is completed / (completed + driver-cancelled) as
 * a whole-number percentage (98, not 0.98) - rider cancellations/no-shows aren't counted against
 * the driver. Null until the driver has at least one completed or driver-cancelled trip, since
 * there's nothing to compute a rate from yet. */
public record DriverTripStatsResponse(int tripsThisWeek, long lifetimeTrips, Integer reliabilityPercent) {
}
