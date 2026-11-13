package org.coryd.trip.dto;

import java.time.Instant;

/**
 * A trip left in progress past its expected arrival plus the configured grace - ops completes or cancels it. boarded
 * riders were picked up, awaitingPickup never were, completed were already dropped off by the driver.
 */
public record OpsStuckTripItem(
        OpsTripItem trip,
        Instant expectedArrivalAt,
        Instant stuckSince,
        long minutesPastExpectedArrival,
        int boarded,
        int awaitingPickup,
        int completed,
        LocationResponse lastSeen,
        Long locationAgeSeconds
) {
}
