package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Everything trip-service actually has on the driver's active route, so the client can build the dashboard's small card or a full tap-through detail screen from one call. Rider names/photos aren't included - no rider-lookup client exists in this service yet (only driver-facing enrichment does); a separate follow-up if the passenger list needs to show names. */
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
        List<ActiveBookingSummary> bookings
) {
    public record ActiveBookingSummary(
            String bookingId,
            String riderId,
            int seatCount,
            BigDecimal fareTotal,
            BookingStatus status,
            Instant pickupConfirmedAt
    ) {
    }
}
