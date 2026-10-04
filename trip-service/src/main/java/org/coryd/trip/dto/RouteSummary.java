package org.coryd.trip.dto;

import java.time.Instant;

/** Batch route lookup - lets a client resolve routePostIds from elsewhere (e.g. the driver earnings screen's trip list) into display text without this service aggregating another service's data itself. */
public record RouteSummary(
        String routePostId,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        int seatsBooked
) {
}
