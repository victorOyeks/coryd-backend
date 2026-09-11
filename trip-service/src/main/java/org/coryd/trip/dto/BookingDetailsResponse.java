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
        String overviewPolyline
) {
}
