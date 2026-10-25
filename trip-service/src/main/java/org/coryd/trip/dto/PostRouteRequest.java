package org.coryd.trip.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Label and note bounds mirror the VARCHAR(255) columns they land in. Without them an
 * over-long Places address reaches the flush as SQLSTATE 22001 and rolls back the whole
 * route post as an opaque 500 instead of a 400 naming the offending field.
 */
public record PostRouteRequest(
        String vehicleId,
        @NotBlank @Size(max = 255) String originLabel,
        @NotNull Double originLat,
        @NotNull Double originLng,
        @NotBlank @Size(max = 255) String destinationLabel,
        @NotNull Double destinationLat,
        @NotNull Double destinationLng,
        @NotNull Instant departureTime,
        @NotNull @Positive Integer seatCapacity,
        @NotNull @Positive BigDecimal farePerSeat,
        @Size(max = 255) String routeNote,
        List<@Valid ReservationRequest> reservations,
        /** The road the driver picked from /maps/route-options (its overviewPolyline). Null = Google's usual road. */
        @Size(max = 20000) String routePolyline,
        /** Short words for that road, e.g. "Ojota, Maryland, Osborne" or the option's viaSummary - riders see it. */
        @Size(max = 255) String viaSummary
) {

    /** Callers that leave the road to Google. */
    public PostRouteRequest(String vehicleId, String originLabel, Double originLat, Double originLng,
                            String destinationLabel, Double destinationLat, Double destinationLng, Instant departureTime,
                            Integer seatCapacity, BigDecimal farePerSeat, String routeNote, List<ReservationRequest> reservations) {
        this(vehicleId, originLabel, originLat, originLng, destinationLabel, destinationLat, destinationLng, departureTime,
                seatCapacity, farePerSeat, routeNote, reservations, null, null);
    }

    /** One seat reservation for a favourite rider, made as part of posting this route - see
     * RouteService.post() for validation (every riderId must actually be a favourite of this
     * driver, and the total seatCount across every entry can't exceed seatCapacity above). */
    public record ReservationRequest(@NotBlank String riderId, @NotNull @Positive Integer seatCount) {
    }
}
