package org.coryd.trip.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** originLabel/destinationLabel are optional place names (e.g. "Ikorodu") - they name the route in drivers' demand list. */
public record SearchRequest(
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotNull Double destinationLat,
        @NotNull Double destinationLng,
        @NotNull Instant desiredDepartureTime,
        String originLabel,
        String destinationLabel
) {
}
