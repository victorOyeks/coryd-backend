package org.coryd.trip.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;

/**
 * Either one ride (desiredDepartureTime) or a repeating one (repeatDays + repeatTime + repeatWeeks),
 * e.g. MONDAY..FRIDAY at "07:00" for 4 weeks. repeatTime is Lagos time.
 */
public record NotifyMeCreateRequest(
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotNull Double destinationLat,
        @NotNull Double destinationLng,
        String originLabel,
        String destinationLabel,
        Instant desiredDepartureTime,
        List<DayOfWeek> repeatDays,
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "repeatTime must be HH:mm, e.g. 07:00") String repeatTime,
        @Min(1) @Max(12) Integer repeatWeeks
) {
}
