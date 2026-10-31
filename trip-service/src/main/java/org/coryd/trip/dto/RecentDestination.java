package org.coryd.trip.dto;

import java.time.Instant;

/** A place this user went to recently - as a rider's drop-off or the end of a trip they drove. */
public record RecentDestination(String label, double lat, double lng, Instant lastUsedAt) {
}
