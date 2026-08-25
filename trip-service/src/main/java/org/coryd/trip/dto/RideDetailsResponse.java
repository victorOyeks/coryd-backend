package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The Ride Details screen: everything RideSearchResult carries (driver/vehicle/rating/
 * pickup-eta) plus the full route preview (distance/duration/via-summary, from
 * MapsService.getRoutePreview) and an estimated arrival time - unlike pickupEtaMinutes,
 * which is rider-to-pickup, these describe the whole origin-to-destination leg.
 */
public record RideDetailsResponse(
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
        int pickupEtaMinutes,
        double routeDistanceKm,
        int routeDurationMinutes,
        String routeDurationText,
        String routeViaSummary,
        Instant estimatedArrivalTime,
        int seatHoldMinutes,
        String routeNote
) {
}
