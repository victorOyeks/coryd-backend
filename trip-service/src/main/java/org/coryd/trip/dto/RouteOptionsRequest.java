package org.coryd.trip.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The driver's road choices between two points. Without via, Google's usual road plus its alternatives;
 * with via, one road through those places in the order given - "I go through Ojota, then Maryland".
 */
public record RouteOptionsRequest(
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotNull Double destinationLat,
        @NotNull Double destinationLng,
        @Size(max = 8, message = "Pick at most 8 places to pass through") List<@Valid @NotNull ViaPoint> via
) {
    public record ViaPoint(@NotNull Double lat, @NotNull Double lng) {
    }
}
