package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Serves both Pay Now (while status=HELD - fareTotal/holdExpiresAt/seatHoldMinutes drive the
 * fare summary and countdown) and Booking Confirmed (once status=CONFIRMED - the rest is the
 * trip summary and driver card). originLabel/departureTime are enough for the client to
 * compose the pickup-reminder text itself; gate-level detail isn't in the data model anywhere
 * ("exact stop shown after booking" is a driver-communicated fact, not a system one).
 */
public record BookingDetailsResponse(
        String bookingId,
        BookingStatus status,
        String originLabel,
        String destinationLabel,
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
        String routeViaSummary
) {
}
