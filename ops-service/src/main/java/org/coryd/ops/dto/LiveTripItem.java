package org.coryd.ops.dto;

import org.coryd.common.enums.LiveTripPhase;

import java.time.Instant;

/**
 * One card on the Live Trips monitor. location is the live pin (only while the driver's app is sending); lastSeen is
 * their last point however old, locationAgeSeconds how old, locationStale true once it's over two minutes. overdue:
 * started and past etaAt. stuck: past the stuck-trip threshold - resolve it from GET /trips/stuck.
 */
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
        TripLocation location,
        TripLocation lastSeen,
        Long locationAgeSeconds,
        boolean locationStale,
        boolean overdue,
        boolean stuck
) {
}
