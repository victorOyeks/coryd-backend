package org.coryd.ops.dto;

import java.time.Instant;

/** A trip left in progress past its expected arrival plus the grace. boarded / awaitingPickup / completed count its seats. */
public record StuckTripItem(
        TripListItem trip,
        Instant expectedArrivalAt,
        Instant stuckSince,
        long minutesPastExpectedArrival,
        int boarded,
        int awaitingPickup,
        int completed,
        TripLocation lastSeen,
        Long locationAgeSeconds
) {
}
