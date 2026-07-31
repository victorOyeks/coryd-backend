package org.coryd.trip.dto;

import java.time.Instant;

/**
 * "available=false" is a normal state (gate not open yet, or the driver hasn't posted since the
 * last TTL expiry), not an error - message carries a human-readable reason for the client to
 * show instead of a blank map.
 */
public record LocationResponse(boolean available, Double lat, Double lng, Instant recordedAt, Integer etaMinutes, String message) {

    public static LocationResponse unavailable(String message) {
        return new LocationResponse(false, null, null, null, null, message);
    }
}
