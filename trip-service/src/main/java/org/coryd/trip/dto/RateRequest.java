package org.coryd.trip.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.RatingDirection;


public record RateRequest(
        @NotNull String raterId,
        @NotNull String rateeId,
        @NotNull RatingDirection direction,
        @Min(1) @Max(5) int stars,
        String comment
) {
}
