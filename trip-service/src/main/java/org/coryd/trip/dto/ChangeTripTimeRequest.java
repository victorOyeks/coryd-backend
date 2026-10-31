package org.coryd.trip.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;

/** Exactly one: the new departure, or minutes to add to the current one (+15 / +30 / +60). */
public record ChangeTripTimeRequest(
        Instant departureTime,
        @Min(value = 1, message = "minutes must be at least 1") @Max(value = 1440, message = "minutes can be at most 1440") Integer minutes
) {
}
