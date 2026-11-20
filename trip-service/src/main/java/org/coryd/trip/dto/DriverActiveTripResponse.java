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
        /** Riders still on the trip only - cancelled bookings are never in here (see cancelledCount). */
        List<BookingSummary> bookings,
        /** Paid bookings on this route that were later cancelled by the rider or the driver. Counted, not listed, so
         * nobody reading bookings can mistake a cancelled rider for a passenger. */
        int cancelledCount,
        /** The next stop and its ETA from the driver's live point - null until the trip is under way and the phone
         * has sent a location in the last two minutes. */
        NextStop nextStop
) {
    public DriverActiveTripResponse withNextStop(NextStop stop) {
        return new DriverActiveTripResponse(routePostId, vehicleId, originLabel, originLat, originLng, destinationLabel,
                destinationLat, destinationLng, departureTime, farePerSeat, seatCapacity, seatsAvailable, seatsBooked,
                status, bookings, cancelledCount, stop);
    }
}
