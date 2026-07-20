package org.coryd.trip.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;


public record BookSeatsRequest(
        @NotNull String riderId,
        @NotNull @Positive Integer seatCount
) {
}
