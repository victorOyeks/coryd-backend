package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AcceptReservationRequest(
        @NotBlank String pickupLabel,
        @NotNull Double pickupLat,
        @NotNull Double pickupLng
) {
}
