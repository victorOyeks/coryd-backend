package org.coryd.trip.dto;

import org.coryd.common.enums.RouteStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OpsTripItem(
        String routePostId,
        String driverId,
        String vehicleId,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        RouteStatus status,
        Integer seatCapacity,
        Integer seatsAvailable,
        int seatsBooked,
        BigDecimal farePerSeat,
        BigDecimal totalFare,
        Double driverRating,
        long driverRatingCount,
        Instant startedAt,
        Instant completedAt
) {
}
