package org.coryd.ops.dto;

import org.coryd.common.enums.LiveTripPhase;

import java.time.Instant;

/** One card on the Live Trips monitor. */
public record LiveTripItem(
        String routePostId,
        String tripRef,
        LiveTripPhase phase,
        String originLabel,
        String destinationLabel,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        Instant departureTime,
        Instant startedAt,
        DriverSummary driver,
        Double driverRating,
        long driverRatingCount,
        Integer seatCapacity,
        int seatsBooked,
        long elapsedMinutes,
        int estimatedDurationMinutes,
        Instant etaAt,
        double progress,
        TripLocation location
) {
}
