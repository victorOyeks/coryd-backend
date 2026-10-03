package org.coryd.ops.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The "View" page for one trip - every passenger who paid, cancellations and no-shows included. */
public record TripDetailResponse(
        TripListItem trip,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        String routeNote,
        Integer estimatedDurationMinutes,
        TripLocation location,
        List<Passenger> passengers
) {
    public record Passenger(String bookingId, String riderId, String name, String phone, String photoUrl,
                            Integer seatCount, BigDecimal fareTotal, BookingStatus status, String pickupLabel,
                            Double pickupLat, Double pickupLng, Instant confirmedAt, Instant pickedUpAt) {
    }
}
