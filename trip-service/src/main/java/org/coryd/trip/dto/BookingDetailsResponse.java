package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Serves both Pay Now (while status=HELD - fareTotal/holdExpiresAt/seatHoldMinutes drive the
 * fare summary and countdown) and Booking Confirmed (once status=CONFIRMED - the rest is the
 * trip summary and driver card). originLabel/departureTime are enough for the client to
 * compose the pickup-reminder text itself; gate-level detail isn't in the data model anywhere
 * ("exact stop shown after booking" is a driver-communicated fact, not a system one).
 * overviewPolyline is Google's encoded polyline for the road-following line between origin
 * and destination (same field/format as RoutePreview) - null if Directions couldn't be
 * reached, since a missing map line shouldn't block the rest of this screen.
 * routePostId is what the client needs to reach the route's Trip Group Chat
 * (GET/POST /routes/{routePostId}/chat/messages and the /topic/routes/{routePostId}/chat
 * WebSocket topic) - the chat is scoped to the whole route, not to a single booking.
 */
public record BookingDetailsResponse(
        String bookingId,
        String routePostId,
        BookingStatus status,
        String originLabel,
        String destinationLabel,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        Instant departureTime,
        Integer seatCount,
        BigDecimal fareTotal,
        BigDecimal farePerSeatSnapshot,
        Instant holdExpiresAt,
        int seatHoldMinutes,
        String driverId,
        String driverName,
        String driverPhone,
        String driverPhotoUrl,
        boolean driverVerified,
        String vehicleMake,
        String vehicleModel,
        String vehiclePlate,
        boolean vehicleHasAC,
        Integer durationMinutes,
        String overviewPolyline
) {
}
