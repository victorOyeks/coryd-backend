package org.coryd.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record VehicleRegistrationRequest(
        @NotBlank String make,
        @NotBlank String model,
        @NotNull Integer year,
        @NotBlank String plateNumber,
        @NotNull @Positive Integer seatCapacity,
        @NotBlank String exteriorPhotoUrl,
        @NotBlank String interiorPhotoUrl,
        @NotBlank String acPhotoUrl
) {
}
