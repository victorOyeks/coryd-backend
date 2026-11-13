package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One trip for the Ops Console's "View" page - every passenger who paid, cancellations and no-shows included, and the
 * trip's timeline. location is the live point (only while fresh); lastSeen the driver's last point however old.
 * cancellation is the trip's own Cancellations Log row once it was called off as a whole. stuck/expectedArrivalAt/
 * stuckSince follow the stuck-trip rule; actions says what ops can do with it now.
 */
public record OpsTripDetailResponse(
        OpsTripItem trip,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        String routeNote,
        Integer estimatedDurationMinutes,
        LocationResponse location,
        List<Passenger> passengers,
        Instant postedAt,
        Instant originalDepartureTime,
        int timeChangeCount,
        Instant expectedArrivalAt,
        boolean stuck,
        Instant stuckSince,
        LocationResponse lastSeen,
        Long locationAgeSeconds,
        OpsCancellationInfo cancellation,
        Actions actions,
        List<OpsTimelineEntry> timeline
) {
    /**
     * riderFaultFeeNow: the late fee kept if ops cancelled this seat as the rider's fault right now (0 when it wouldn't
     * apply). cancellation: this seat's Cancellations Log row once it was cancelled.
     */
    public record Passenger(String bookingId, String riderId, Integer seatCount, BigDecimal fareTotal, BookingStatus status,
                            String pickupLabel, Double pickupLat, Double pickupLng, Instant confirmedAt, Instant pickedUpAt,
                            String dropoffLabel, Instant completedAt, boolean cancellable, BigDecimal riderFaultFeeNow,
                            OpsCancellationInfo cancellation) {
    }

    /** cancel: POST .../cancel is allowed. resolve: the trip is stuck, POST .../resolve is allowed. */
    public record Actions(boolean cancel, boolean resolve) {
    }
}
