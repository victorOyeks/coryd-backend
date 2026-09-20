package org.coryd.trip.event;

import java.time.Instant;

public record RouteUpdateEvent(String routePostId, String overviewPolyline, double distanceKm,
                                int durationMinutes, Instant updatedAt) {
}
