package org.coryd.trip.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The driver's route ends, plus the road they picked from /maps/route-options if any - null = Google's usual road. */
public record FareSuggestionRequest(
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotNull Double destinationLat,
        @NotNull Double destinationLng,
        @Size(max = 20000) String routePolyline
) {
}
