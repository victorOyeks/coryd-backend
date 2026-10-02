package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The Ride Details screen: everything RideSearchResult carries (driver/vehicle/rating/
 * pickup-eta) plus origin/destination coordinates and the road-following route line
 * between them. {@code overviewPolyline} is Google's encoded polyline (same format/field
 * as {@link RoutePreview}) - the app's Maps SDK key can't call Directions directly (a
 * different key-restriction model than the Maps SDK), so this is computed server-side,
 * not on-device.
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
        String routeNote,
        String overviewPolyline,
        boolean bookable,
        String unavailableReason
) {
}
