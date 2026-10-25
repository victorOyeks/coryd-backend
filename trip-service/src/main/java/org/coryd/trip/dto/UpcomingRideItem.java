package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record UpcomingRideItem(
        String bookingId,
        String routePostId,
        BookingStatus status,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        Integer seatCount,
        BigDecimal fareTotal,
        String pickupLabel,
        String driverName,
        String vehiclePlate,
        /** Where this rider gets out - the route's end when they ride all the way. */
        String dropoffLabel
) {
}
