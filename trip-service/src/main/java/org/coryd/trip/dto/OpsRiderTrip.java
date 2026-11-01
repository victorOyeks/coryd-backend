package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** One trip in a rider's history on the Ops Console. driverRating is the driver's average, as riders see it. */
public record OpsRiderTrip(
        String bookingId,
        String routePostId,
        BookingStatus status,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        int seatCount,
        BigDecimal fareTotal,
        String driverId,
        String driverName,
        Double driverRatingAvg,
        Long driverRatingCount
) {
}
