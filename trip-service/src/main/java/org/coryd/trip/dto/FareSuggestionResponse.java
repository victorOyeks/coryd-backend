package org.coryd.trip.dto;

import java.math.BigDecimal;

/** suggestedFarePerSeat = distanceKm x ratePerKm, rounded, never below the minimum. The same for every rider on the
 * route, wherever they join - distanceKm is always the driver's whole road. */
public record FareSuggestionResponse(
        double distanceKm,
        BigDecimal ratePerKm,
        BigDecimal suggestedFarePerSeat
) {
}
