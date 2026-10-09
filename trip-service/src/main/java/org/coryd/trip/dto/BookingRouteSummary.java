package org.coryd.trip.dto;

import java.time.Instant;

public record BookingRouteSummary(
        String bookingId,
        String routePostId,
        String originLabel,
        String destinationLabel,
        Instant departureTime
) {
}
