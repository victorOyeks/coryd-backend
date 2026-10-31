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
        List<SearchedRoute> topSearchedRoutes,
        /** Leaving now: trips posted right before they left, riders who waited for one, and where no car came. */
        LeavingNow leavingNow
) {
    /**
     * tripsPostedLeavingNow: posted inside the half hour before they left. ridersWaited: "notify me" for about the
     * moment it was made; ridersFoundASeat: those that got a seat. unmetAreas: where waiting riders were going and no
     * car came, most first. timeChanges/lastMinuteTimeChanges: drivers moving their trips instead of cancelling.
     */
    public record LeavingNow(long tripsPostedLeavingNow, long seatsBookedOnThem, long ridersWaited, long ridersFoundASeat,
                             Double waitFoundSeatRate, List<UnmetArea> unmetAreas, long timeChanges, long lastMinuteTimeChanges) {
    }

    public record UnmetArea(String destinationLabel, long riders) {
    }

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
