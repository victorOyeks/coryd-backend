package org.coryd.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * pickup: on the driver's road (the search card's pickup) - the driver doesn't leave it, so a pickup
 * further than a walk from it is refused. dropoff is optional: where the rider gets out, shown to the driver.
 */
public record BookSeatsRequest(
        String riderId,
        @NotNull @Positive Integer seatCount,
        @NotBlank String pickupLabel,
        @NotNull Double pickupLat,
        @NotNull Double pickupLng,
        @Size(max = 255) String dropoffLabel,
        Double dropoffLat,
        Double dropoffLng
) {
    /** Older apps that send only the pickup. */
    public BookSeatsRequest(String riderId, Integer seatCount, String pickupLabel, Double pickupLat, Double pickupLng) {
        this(riderId, seatCount, pickupLabel, pickupLat, pickupLng, null, null, null);
    }
}
