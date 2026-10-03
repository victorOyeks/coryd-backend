package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** One trip for the Ops Console's "View" page - every passenger who paid, cancellations and no-shows included. */
public record OpsTripDetailResponse(
        OpsTripItem trip,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        String routeNote,
        Integer estimatedDurationMinutes,
        LocationResponse location,
        List<Passenger> passengers
) {
    public record Passenger(String bookingId, String riderId, Integer seatCount, BigDecimal fareTotal, BookingStatus status,
                            String pickupLabel, Double pickupLat, Double pickupLng, Instant confirmedAt, Instant pickedUpAt) {
    }
}
