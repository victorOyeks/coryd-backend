package org.coryd.trip.dto;

import java.util.List;

/**
 * Raw Directions API result for an origin -> waypoints -> destination call with
 * optimize:true - Maps-domain only (GoogleMapsClient/MapsService), no booking IDs.
 * {@code waypointOrder} is Google's own optimized visiting order: 0-based indices
 * into the waypoint list as it was sent, not necessarily 0,1,2... - the caller
 * (RouteService) maps these back to booking IDs.
 */
public record DirectionsMultiStopResult(double distanceKm, int durationMinutes, String overviewPolyline,
                                         List<Integer> waypointOrder) {
}
