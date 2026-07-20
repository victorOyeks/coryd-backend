package org.coryd.trip.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record SearchRequest(
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotNull Double destinationLat,
        @NotNull Double destinationLng,
        @NotNull Instant desiredDepartureTime
) {
}
