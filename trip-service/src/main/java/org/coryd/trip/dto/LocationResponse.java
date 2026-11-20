package org.coryd.trip.dto;

import java.time.Instant;

/**
 * "available=false" is a normal state (gate not open yet, or the driver hasn't posted since the
 * last TTL expiry), not an error - message carries a human-readable reason for the client to
 * show instead of a blank map. No ETA here - this is a raw-point relay, mobile derives ETA
 * itself (it already has the map SDK) rather than this service guessing at it.
 */
public record LocationResponse(boolean available, Double lat, Double lng, Instant recordedAt, String message,
                               /* Degrees clockwise from north the car is facing, from the phone's GPS - null when the phone didn't know
                                  (stopped, or an older app). The map turns the car icon with it when the car is off the planned route. */
                               Double heading,
                               /* Metres per second from the phone's GPS - null when unknown. */
                               Double speed) {

    public LocationResponse(boolean available, Double lat, Double lng, Instant recordedAt, String message) {
        this(available, lat, lng, recordedAt, message, null, null);
    }

    public static LocationResponse unavailable(String message) {
        return new LocationResponse(false, null, null, null, message);
    }
}
