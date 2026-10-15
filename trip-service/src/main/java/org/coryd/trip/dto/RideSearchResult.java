package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A search-result card, enriched with driver/vehicle/rating data pulled from profile-service
 * and this service's own Rating table - see RouteService.enrich(). pickupEtaMinutes comes from
 * one batched Google Distance Matrix call across the whole result page (rider's position against
 * every result's origin), not one Directions call per row - null if that call failed, so a Maps
 * hiccup degrades the ETA column instead of failing the whole search.
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
        Integer pickupEtaMinutes,
        String routeNote,
        /** True when the rider joins part-way: pickup and dropoff are on the driver's road, not at its ends. */
        boolean alongTheWay,
        /** Where to meet the car and when - null when the rider is matched at the route's start, as before. */
        RideStop pickup,
        RideStop dropoff
) {
}
