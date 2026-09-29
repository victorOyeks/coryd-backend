package org.coryd.trip.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.coryd.common.enums.RatingDirection;


public record RateRequest(
        String raterId,
        String rateeId,
        RatingDirection direction,
        @Min(1) @Max(5) int stars,
        String comment
) {
}
