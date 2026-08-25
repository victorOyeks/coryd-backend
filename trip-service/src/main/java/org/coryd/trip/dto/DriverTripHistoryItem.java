package org.coryd.trip.dto;

import java.time.Instant;
import java.util.List;

/** One row per posted route, not per booking - "3 passengers, ₦8,780 net" is the whole route's outcome. completedBookingIds is what the client uses to look up net earnings (payment-service, a separate call) and sum them per route. */
public record DriverTripHistoryItem(
        String routePostId,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        int passengerCount,
        List<String> completedBookingIds
) {
}
