package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The Ride Details screen: everything RideSearchResult carries (driver/vehicle/rating/
 * pickup-eta) plus origin/destination coordinates - the route's own distance/duration/via-
 * summary/ETA is no longer computed here, mobile derives it on-device (it already renders the
 * map, so it calls Google Directions itself for this single origin/destination pair rather than
 * this service doing it server-side and shipping the text down).
 */
public record RideDetailsResponse(
        String routePostId,
        String originLabel,
        String destinationLabel,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
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
        int seatHoldMinutes,
        String routeNote
) {
}
