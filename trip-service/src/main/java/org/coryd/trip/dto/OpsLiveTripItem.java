package org.coryd.trip.dto;

import org.coryd.common.enums.LiveTripPhase;

import java.time.Instant;

/**
 * One card on the Ops Console's Live Trips monitor. location is the live point (available only while fresh, the same
 * as the riders' map); lastSeen is the driver's last reported point however old, with its age in locationAgeSeconds,
 * so a trip whose driver went quiet still shows where it was. overdue: started and past etaAt. stuck: past the
 * stuck-trip threshold (see StuckTripProperties) - resolve it from the stuck queue.
 */
public record OpsLiveTripItem(
        String routePostId,
        String driverId,
        String originLabel,
        String destinationLabel,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        Instant departureTime,
        Instant startedAt,
        LiveTripPhase phase,
        Integer seatCapacity,
        int seatsBooked,
        int estimatedDurationMinutes,
        long elapsedMinutes,
        Instant etaAt,
        double progress,
        Double driverRating,
        long driverRatingCount,
        LocationResponse location,
        LocationResponse lastSeen,
        Long locationAgeSeconds,
        boolean locationStale,
        boolean overdue,
        boolean stuck
) {
}
