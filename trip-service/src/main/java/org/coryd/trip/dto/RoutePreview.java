package org.coryd.trip.dto;

/**
 * Distance/duration/route-summary between two points, as shown on the ride-details
 * screen ("~55 min · 32km via Ketu bypass"). {@code viaSummary} is Google's own short
 * route description (Directions API {@code routes[0].summary}), not derived locally.
 */
public record RoutePreview(double distanceKm, int durationMinutes, String durationText, String viaSummary) {
}
