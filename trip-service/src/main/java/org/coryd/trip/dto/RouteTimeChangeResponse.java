package org.coryd.trip.dto;

import java.time.Instant;

/**
 * The trip after its time changed. timeChangeCount counts changes made with riders booked - changesLeft is how
 * many more of those are allowed. ridersTold: paid riders pushed about the new time.
 */
public record RouteTimeChangeResponse(
        String routePostId,
        Instant previousDepartureTime,
        Instant departureTime,
        Instant originalDepartureTime,
        int timeChangeCount,
        int changesLeft,
        int ridersTold
) {
}
