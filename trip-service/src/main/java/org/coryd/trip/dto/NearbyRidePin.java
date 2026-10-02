package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record NearbyRidePin(
        double lat,
        double lng,
        String routePostId,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        Integer seatsAvailable,
        BigDecimal farePerSeat,
        String driverFirstName,
        String driverPhotoUrl,
        Double driverRating,
        String vehicleMake,
        String vehicleModel
) {
}
