package org.coryd.trip.dto;

import org.coryd.common.enums.RouteStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** One row per posted route, not per booking - "3 passengers, ₦4,500 earned" is the whole route's outcome. totalEarned is gross (fare collected, no commission subtracted) and, like seatsBooked, is summed across every non-HELD booking regardless of the route's own status - a cancelled route still shows what it actually had booked. */
public record DriverTripHistoryItem(
        String routePostId,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        RouteStatus status,
        int seatsBooked,
        BigDecimal totalEarned
) {
}
