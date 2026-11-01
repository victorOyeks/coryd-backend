package org.coryd.ops.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Everything a rider's trip receipt shows - the console renders it and makes the PDF. Driver and car are as they were
 * when the seat was booked. payment is null if payment-service has no record or couldn't be reached.
 */
public record RiderReceipt(
        String bookingId,
        String ref,
        BookingStatus status,
        DriverSummary rider,
        Route route,
        Instant bookedAt,
        Instant paidAt,
        Instant pickedUpAt,
        Instant completedAt,
        Driver driver,
        int seatCount,
        BigDecimal farePerSeat,
        BigDecimal fareTotal,
        RiderTripItem.Payment payment
) {
    public record Route(String routePostId, String originLabel, String destinationLabel, String pickupLabel, String dropoffLabel,
                        Instant departureTime) {
    }

    public record Driver(String id, String ref, String name, String vehiclePlate) {
    }
}
