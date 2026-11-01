package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a rider's receipt shows, from the trip's side: the route and where they got on and off, the driver and car as they
 * were when booked, the seats and fare, and when it was paid for, picked up and finished. Payment and refunds come from
 * payment-service.
 */
public record OpsRiderReceipt(
        String bookingId,
        String riderId,
        String routePostId,
        BookingStatus status,
        String originLabel,
        String destinationLabel,
        String pickupLabel,
        String dropoffLabel,
        Instant departureTime,
        Instant bookedAt,
        Instant paidAt,
        Instant pickedUpAt,
        Instant completedAt,
        String driverId,
        String driverName,
        String vehiclePlate,
        int seatCount,
        BigDecimal farePerSeat,
        BigDecimal fareTotal
) {
}
