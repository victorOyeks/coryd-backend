package org.coryd.trip.dto;

import java.time.Instant;

/**
 * One moment in a trip's life on the Ops Console's detail page, oldest first. type is one of POSTED, TIME_CHANGED,
 * BOOKED, DRIVER_EN_ROUTE, DRIVER_ARRIVED, PICKED_UP, NO_SHOW, BOOKING_COMPLETED, BOOKING_CANCELLED, BOOKING_UPDATED,
 * STARTED, COMPLETED, CANCELLED, SOS, SAFETY_CHECK, OPS_ACTION. actor is DRIVER, RIDER, SYSTEM or OPS; actorId is the
 * person when known. bookingId and riderId are set for a seat's own events.
 */
public record OpsTimelineEntry(
        Instant at,
        String type,
        String actor,
        String actorId,
        String actorName,
        String bookingId,
        String riderId,
        String detail
) {
}
