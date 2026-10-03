package org.coryd.trip.dto;

import org.coryd.common.enums.LiveTripPhase;

import java.time.Instant;

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
        LocationResponse location
) {
}
