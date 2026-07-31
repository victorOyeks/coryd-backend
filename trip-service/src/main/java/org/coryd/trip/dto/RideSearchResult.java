package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A search-result card, enriched with driver/vehicle/rating data pulled from profile-service
 * and this service's own Rating table - see RouteService.enrich(). pickupEtaMinutes is a
 * straight-line-distance estimate (Haversine + an assumed speed), not a Google Directions
 * call - doing that per result would multiply external API calls per search.
 */
public record RideSearchResult(
        String routePostId,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        BigDecimal farePerSeat,
        Integer seatsAvailable,
        String driverId,
        String driverName,
        String driverPhotoUrl,
        boolean driverVerified,
        String vehicleMake,
        String vehicleModel,
        String vehiclePlate,
        boolean vehicleHasAC,
        Double ratingAvg,
        long ratingCount,
        int pickupEtaMinutes
) {
}
