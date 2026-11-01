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
        String unavailableReason,
        /** The road the driver picked, in words - null when they left it to Google. overviewPolyline is that road. */
        String viaSummary,
        /** The car's colour, to spot it in a car park - null if the driver never gave one. */
        String vehicleColour,
        /** The departure before the driver first changed it - null while it never has. */
        java.time.Instant originalDepartureTime,
        /** How many times the driver changed this trip's time with riders booked. */
        int timeChangeCount,
        /** When booking closes - the departure time plus the grace period, or sooner if the driver starts. Null once the
         * trip isn't taking bookings (started, finished or cancelled). */
        java.time.Instant bookableUntil
) {
}
