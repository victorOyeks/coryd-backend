package org.coryd.ops.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The "View" page for one trip - every passenger who paid, cancellations and no-shows included, each with their
 * payment and refund; the trip's timeline; whether it's stuck; and what ops can do with it now (actions).
 */
public record TripDetailResponse(
        TripListItem trip,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        String routeNote,
        Integer estimatedDurationMinutes,
        TripLocation location,
        List<Passenger> passengers,
        Instant postedAt,
        Instant originalDepartureTime,
        int timeChangeCount,
        Instant expectedArrivalAt,
        boolean stuck,
        Instant stuckSince,
        TripLocation lastSeen,
        Long locationAgeSeconds,
        CancellationItem cancellation,
        Actions actions,
        List<TimelineEntry> timeline
) {
    /**
     * cancellable: POST /bookings/{bookingId}/cancel is allowed. riderFaultFeeNow: the late fee kept if ops cancelled it
     * as the rider's fault now. payment: payment-service's record (null if it couldn't be reached). refund: where this
     * seat's refund is. cancellation: its Cancellations Log row once cancelled.
     */
    public record Passenger(String bookingId, String bookingRef, String riderId, String name, String phone, String photoUrl,
                            Integer seatCount, BigDecimal fareTotal, BookingStatus status, String pickupLabel,
                            Double pickupLat, Double pickupLng, String dropoffLabel, Instant confirmedAt, Instant pickedUpAt,
                            Instant completedAt, boolean cancellable, BigDecimal riderFaultFeeNow, PaymentSummary payment,
                            RefundOutcome refund, CancellationItem cancellation) {
    }

    /** cancel: the whole trip can be cancelled. resolve: it's stuck, so POST /trips/{id}/resolve is allowed. */
    public record Actions(boolean cancel, boolean resolve) {
    }
}
