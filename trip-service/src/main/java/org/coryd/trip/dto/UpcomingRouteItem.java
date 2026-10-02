package org.coryd.trip.dto;

import org.coryd.common.enums.RouteStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record UpcomingRouteItem(
        String routePostId,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        RouteStatus status,
        Integer seatCapacity,
        Integer seatsAvailable,
        int seatsBooked,
        BigDecimal farePerSeat
) {
}
