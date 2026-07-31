package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.time.Instant;

/** The read-only view a "share trip" link resolves to - no auth, since the whole point is the recipient has no account. */
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
        LocationResponse location
) {
}
