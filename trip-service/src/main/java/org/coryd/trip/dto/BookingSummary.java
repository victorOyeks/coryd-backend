package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Shared shape for "who's booked on this route" - used by both the active-trip card and the Passenger Manifest. riderName/riderPhotoUrl come from profile-service's batch rider lookup (same client TripReceiptResponse's passengers already use). riderRatingAvg/riderRatingCount are the rider's own aggregate rating from past trips (how previous drivers rated them) - lets a driver see who they're picking up, same idea as a rider seeing the driver's rating on a search result. */
public record BookingSummary(
        String bookingId,
        String riderId,
        String riderName,
        String riderPhotoUrl,
        int seatCount,
        BigDecimal fareTotal,
        BookingStatus status,
        Instant pickupConfirmedAt,
        String pickupLabel,
        Double pickupLat,
        Double pickupLng,
        Double riderRatingAvg,
        Long riderRatingCount
) {
}
