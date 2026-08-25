package org.coryd.trip.dto;

import org.coryd.common.enums.RouteStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Everything trip-service actually has on the driver's active route, so the client can build the dashboard's small card or a full tap-through detail screen from one call. */
public record DriverActiveTripResponse(
        String routePostId,
        String vehicleId,
        String originLabel,
        Double originLat,
        Double originLng,
        String destinationLabel,
        Double destinationLat,
        Double destinationLng,
        Instant departureTime,
        BigDecimal farePerSeat,
        int seatCapacity,
        int seatsAvailable,
        int seatsBooked,
        RouteStatus status,
        List<BookingSummary> bookings
) {
}
