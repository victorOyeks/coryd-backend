package org.coryd.trip.dto;

import java.time.Instant;

/**
 * Where the driver is heading next and how far off it is, from their latest live point. Chosen the same way the
 * app's trip card chooses (src/lib/tripStops.ts + summariseDriverTrip): the first rider still to collect in stop
 * order, else the first rider aboard who gets out before the route's end, else the destination.
 *
 * @param bookingId      the rider's booking for PICKUP/DROPOFF, null for DESTINATION
 * @param etaMinutes     driving minutes from the driver's live point - at least 1
 * @param distanceMeters road distance (or the straight-line estimate when estimated is true)
 * @param estimated      true when Google couldn't answer and this is a straight-line guess at city speed
 * @param computedAt     when this ETA was worked out - it is reused for up to a minute
 */
public record NextStop(
        Kind kind,
        String bookingId,
        String label,
        double lat,
        double lng,
        int etaMinutes,
        int distanceMeters,
        boolean estimated,
        Instant computedAt
) {
    public enum Kind { PICKUP, DROPOFF, DESTINATION }
}
