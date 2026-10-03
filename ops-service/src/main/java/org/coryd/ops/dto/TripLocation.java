package org.coryd.ops.dto;

import java.time.Instant;

public record TripLocation(
        boolean available,
        Double lat,
        Double lng,
        Instant recordedAt
) {
    public static final TripLocation UNAVAILABLE = new TripLocation(false, null, null, null);
}
