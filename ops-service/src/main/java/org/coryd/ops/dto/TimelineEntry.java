package org.coryd.ops.dto;

import java.time.Instant;

/** One moment on a trip's timeline, oldest first - see docs/ops-console/trips-bookings.md for the types. */
public record TimelineEntry(
        Instant at,
        String type,
        String actor,
        String actorId,
        String actorName,
        String bookingId,
        String riderId,
        String riderName,
        String detail
) {
}
