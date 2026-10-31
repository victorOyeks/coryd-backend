package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record BookingDetailsResponse(
        String bookingId,
        String routePostId,
        BookingStatus status,
        String originLabel,
        String destinationLabel,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        Instant departureTime,
        Integer seatCount,
        BigDecimal fareTotal,
        BigDecimal farePerSeatSnapshot,
        Instant holdExpiresAt,
        int seatHoldMinutes,
        String driverId,
        String driverName,
        String driverPhone,
        String driverPhotoUrl,
        boolean driverVerified,
        String vehicleMake,
        String vehicleModel,
        String vehiclePlate,
        boolean vehicleHasAC,
        Integer durationMinutes,
        String overviewPolyline,
        String pickupCode,
        /** Where this rider gets in - originLabel/destinationLabel above are the driver's whole route. */
        String pickupLabel,
        Double pickupLat,
        Double pickupLng,
        /** Where this rider gets out - the route's end when they ride all the way. */
        String dropoffLabel,
        Double dropoffLat,
        Double dropoffLng,
        /** The car's colour, to spot it at pickup - null if the driver never gave one. */
        String vehicleColour,
        /** The departure before the driver first changed it - null while it never has. */
        Instant originalDepartureTime,
        /** How many times the driver changed the time with riders booked. */
        int timeChangeCount,
        /** The trip moved far enough from what this rider booked that cancelling is free, with a full refund. */
        boolean freeCancellation,
        /** Roughly what a solo cab would charge for this rider's stretch - null when it can't be worked out or is turned off. */
        BigDecimal soloCabEstimate,
        /** soloCabEstimate less what they paid, never below 0 - "You saved about N". Null with soloCabEstimate. */
        BigDecimal estimatedSaving
) {
}
