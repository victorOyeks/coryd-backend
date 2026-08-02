package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.SavedPlaceType;

public record SavePlaceRequest(
        @NotBlank String label,
        @NotBlank String formattedAddress,
        @NotNull Double lat,
        @NotNull Double lng,
        /** Null defaults to GENERAL - the plain "Add a place" flow doesn't force a Pickup/Destination tag. */
        SavedPlaceType type
) {
}
