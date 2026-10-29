package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;

import java.time.Instant;

/** The read-only view a "share trip" link resolves to - no auth, since the whole point is the recipient has no account.
 * status is the shared booking's (null when a driver shared the whole trip); tripStatus is the trip's own. The ends and
 * routePolyline (the driver's picked road, null if they didn't pick one) let the page draw the trip before any location. */
public record SharedTripResponse(
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        BookingStatus status,
        String driverName,
        String driverPhotoUrl,
        boolean driverVerified,
        String vehicleMake,
        String vehicleModel,
        String vehiclePlate,
        boolean vehicleHasAC,
        LocationResponse location,
        RouteStatus tripStatus,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        String routePolyline
) {
}
