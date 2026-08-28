package org.coryd.trip.dto;

/**
 * Distance/duration/route-summary between two points, as shown on the ride-details
 * screen ("~55 min · 32km via Ketu bypass"). {@code viaSummary} is Google's own short
 * route description (Directions API {@code routes[0].summary}), not derived locally.
 * {@code overviewPolyline} is Google's encoded polyline string (Directions API
 * {@code routes[0].overview_polyline.points}) - the client decodes it locally into
 * the road-following points for the map, instead of drawing a straight line between
 * the two raw coordinates.
 */
public record RoutePreview(double distanceKm, int durationMinutes, String durationText, String viaSummary,
                            String overviewPolyline) {
}
