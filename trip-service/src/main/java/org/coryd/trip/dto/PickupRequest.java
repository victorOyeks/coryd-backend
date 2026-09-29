package org.coryd.trip.dto;

import jakarta.validation.constraints.Pattern;

public record PickupRequest(
        @Pattern(regexp = "\\d{4}", message = "Pickup code must be 4 digits") String pickupCode
) {
}
