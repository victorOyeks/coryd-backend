package org.coryd.trip.dto;

import java.time.Instant;

public record DriverActiveTripResponse(
        String routePostId,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        int seatsBooked,
        int seatCapacity
) {
}
