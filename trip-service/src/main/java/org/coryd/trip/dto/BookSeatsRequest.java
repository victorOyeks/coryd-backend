package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;


public record BookSeatsRequest(
        String riderId,
        @NotNull @Positive Integer seatCount,
        @NotBlank String pickupLabel,
        @NotNull Double pickupLat,
        @NotNull Double pickupLng
) {
}
