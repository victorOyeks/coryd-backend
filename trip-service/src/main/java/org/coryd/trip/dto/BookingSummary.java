package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Shared shape for "who's booked on this route" - used by both the active-trip card and the Passenger Manifest. Rider name/photo aren't included - no rider-lookup client exists in trip-service yet, only driver-facing enrichment does. */
public record BookingSummary(
        String bookingId,
        String riderId,
        int seatCount,
        BigDecimal fareTotal,
        BookingStatus status,
        Instant pickupConfirmedAt
) {
}
