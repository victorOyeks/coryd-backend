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
        /** Where the rider gets out - null means the route's end. */
        String dropoffLabel,
        Double dropoffLat,
        Double dropoffLng,
        Double riderRatingAvg,
        Long riderRatingCount,
        /** Passed the NIN + selfie check - the "Verified rider" badge on the driver's side. */
        boolean riderVerified,
        /** How far along the route's road the pickup is, in metres from the start (0 = boards at the start). Sort the
         * passenger list by this. Null only for a route without a stored line - keep the existing order then. */
        Double pickupAlongRouteMeters,
        /** Same for where they get out (the route's length if they ride to the end) - drop-offs in this order. */
        Double dropoffAlongRouteMeters
) {
}
