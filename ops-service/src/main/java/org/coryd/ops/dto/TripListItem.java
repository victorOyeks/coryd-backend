package org.coryd.ops.dto;

import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of the Trips table. group is the tab it belongs to. */
public record TripListItem(
        String routePostId,
        String tripRef,
        RouteStatus status,
        OpsTripStatus group,
        DriverSummary driver,
        VehicleSummary vehicle,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        Integer seatCapacity,
        int seatsBooked,
        BigDecimal farePerSeat,
        BigDecimal totalFare,
        Double driverRating,
        long driverRatingCount,
        Instant startedAt,
        Instant completedAt
) {
}
