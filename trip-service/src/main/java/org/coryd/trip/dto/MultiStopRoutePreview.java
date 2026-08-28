package org.coryd.trip.dto;

import java.util.List;

/**
 * Road-following route through a route post's whole current manifest (origin -> every
 * still-active pickup -> destination), for the driver's Ready to Depart / Live Trip map
 * and the rider's Live Trip map - the multi-stop counterpart to {@link RoutePreview}'s
 * single origin/destination pair. {@code orderedBookingIds} reflects Google's own
 * optimized visiting order (Directions optimize:true), not booking-creation order -
 * empty when there are no confirmed pickups yet (falls back to a direct preview).
 */
public record MultiStopRoutePreview(double distanceKm, int durationMinutes, String overviewPolyline,
                                     List<String> orderedBookingIds) {
}
