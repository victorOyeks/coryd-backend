package org.coryd.trip.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/** A regular route: days + departureTime ("07:00", Lagos time). standingSeats reserve seats for favourite riders on every trip. */
public record RouteTemplateRequest(
        String vehicleId,
        @NotBlank @Size(max = 255) String originLabel,
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotBlank @Size(max = 255) String destinationLabel,
        @NotNull Double destinationLat,
        @NotNull Double destinationLng,
        @NotEmpty List<DayOfWeek> days,
        @NotNull @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "departureTime must be HH:mm, e.g. 07:00") String departureTime,
        @NotNull @Positive Integer seatCapacity,
        @NotNull @Positive BigDecimal farePerSeat,
        @Size(max = 255) String routeNote,
        LocalDate activeUntil,
        @Valid List<PostRouteRequest.ReservationRequest> standingSeats
) {
}
