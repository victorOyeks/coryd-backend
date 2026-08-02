package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record TripHistoryItem(
        String bookingId,
        BookingStatus status,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        BigDecimal fareTotal,
        String driverName,
        Double ratingAvg,
        Long ratingCount
) {
}
