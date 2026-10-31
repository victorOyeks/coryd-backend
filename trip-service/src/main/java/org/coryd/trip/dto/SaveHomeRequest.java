package org.coryd.trip.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** formattedAddress is optional - the label is used when it's left out. */
public record SaveHomeRequest(
        @NotBlank @Size(max = 100) String label,
        @Size(max = 255) String formattedAddress,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng
) {
}
