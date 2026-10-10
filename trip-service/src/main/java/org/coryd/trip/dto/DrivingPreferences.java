package org.coryd.trip.dto;

/**
 * "Where do you usually drive?" - used to match rider demand until a driver has posted routes, and
 * the on/off switch for demand pushes. Both the request and the response.
 */
public record DrivingPreferences(
        Double startLat,
        Double startLng,
        String startLabel,
        Double endLat,
        Double endLng,
        String endLabel,
        Boolean mornings,
        Boolean evenings,
        Boolean demandAlerts
) {
}
