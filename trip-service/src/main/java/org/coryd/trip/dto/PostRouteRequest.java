package org.coryd.trip.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Label and note bounds mirror the VARCHAR(255) columns they land in. Without them an
 * over-long Places address reaches the flush as SQLSTATE 22001 and rolls back the whole
 * route post as an opaque 500 instead of a 400 naming the offending field.
 */
public record PostRouteRequest(
        String vehicleId,
        @NotBlank @Size(max = 255) String originLabel,
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotBlank @Size(max = 255) String destinationLabel,
        @NotNull Double destinationLat,
        @NotNull Double destinationLng,
        @NotNull Instant departureTime,
        @NotNull @Positive Integer seatCapacity,
        @NotNull @Positive BigDecimal farePerSeat,
        @Size(max = 255) String routeNote,
        List<@Valid ReservationRequest> reservations
) {

    /** One seat reservation for a favourite rider, made as part of posting this route - see
     * RouteService.post() for validation (every riderId must actually be a favourite of this
     * driver, and the total seatCount across every entry can't exceed seatCapacity above). */
    public record ReservationRequest(@NotBlank String riderId, @NotNull @Positive Integer seatCount) {
    }
}
