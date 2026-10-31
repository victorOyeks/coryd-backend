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
        @Min(1) @Max(12) Integer repeatWeeks,
        /** One-off only: stop waiting this many minutes from now (e.g. 45 while standing in a car park) instead of
         * an hour past desiredDepartureTime. */
        @Min(value = 5, message = "expiresInMinutes must be at least 5") @Max(value = 720, message = "expiresInMinutes can be at most 720") Integer expiresInMinutes,
        /** Repeating only: a ride at any time on those days counts, not only around repeatTime - e.g. after a church
         * service that can run late. repeatTime is still sent, as the usual time. */
        Boolean anyTimeOfDay
) {
}
