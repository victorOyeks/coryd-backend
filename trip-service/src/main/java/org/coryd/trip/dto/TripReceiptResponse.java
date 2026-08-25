package org.coryd.trip.dto;

import org.coryd.common.enums.RouteStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The Receipt screen - totalEarned is gross (fare collected, no commission subtracted), a different figure from the Trip Complete settlement screen's net-to-wallet. durationMinutes is null if the route never actually started/completed (e.g. cancelled before departure). */
public record TripReceiptResponse(
        String routePostId,
        RouteStatus status,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        Integer durationMinutes,
        List<ReceiptPassenger> passengers,
        BigDecimal farePerSeat,
        int seatsBooked,
        BigDecimal totalEarned,
        String receiptReference
) {
}
