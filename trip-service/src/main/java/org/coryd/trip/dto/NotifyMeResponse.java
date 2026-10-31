package org.coryd.trip.dto;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;

/** repeatDays/repeatTime are set on a repeating request, desiredDepartureTime on a one-off. */
public record NotifyMeResponse(
        String id,
        String originLabel,
        String destinationLabel,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        Instant desiredDepartureTime,
        List<DayOfWeek> repeatDays,
        String repeatTime,
        Instant activeUntil,
        /** Repeating: any ride on those days counts, not only around repeatTime. */
        boolean anyTimeOfDay
) {
}
