package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record PostRouteRequest(
        String vehicleId,
        @NotBlank String originLabel,
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotBlank String destinationLabel,
        @NotNull Double destinationLat,
        @NotNull Double destinationLng,
        @NotNull Instant departureTime,
        @NotNull @Positive Integer seatCapacity,
        @NotNull @Positive BigDecimal farePerSeat,
        String routeNote
) {
}
