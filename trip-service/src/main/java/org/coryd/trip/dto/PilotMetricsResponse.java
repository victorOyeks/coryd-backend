package org.coryd.trip.dto;

import java.time.Instant;
import java.util.List;

/**
 * The numbers that say whether the pilot is working, over a window. Rates are 0-1, null when there was
 * nothing to divide by.
 */
public record PilotMetricsResponse(
        Instant from,
        Instant to,
        Totals totals,
        /** Of riders who completed a trip in the week before the window, the share who completed another in it. */
        Double weekTwoRepeatRate,
        long notifyMeCreated,
        long notifyMeBooked,
        List<SearchedRoute> topSearchedRoutes
) {
    public record Totals(
            long searches,
            long searchesWithResults,
            Double searchSuccessRate,
            long ridersSearching,
            long ridersBooked,
            Double searchToBookingRate,
            long routesPosted,
            long seatsOffered,
            long seatsBooked,
            Double fillRate
    ) {
    }

    /** By the place names riders picked - the same place typed two ways counts twice. */
    public record SearchedRoute(String originLabel, String destinationLabel, long searches, long riders, long searchesWithNoResults) {
    }
}
