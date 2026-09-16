package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.util.List;

public record TripStatsResponse(
        long routesPosted,
        long routesCompleted,
        long routesCancelled,
        long seatsOffered,
        long activeDrivers,
        long bookingsConfirmed,
        long bookingsCompleted,
        long bookingsCancelledRider,
        long bookingsCancelledDriver,
        long noShows,
        long seatsBooked,
        long distinctRiders,
        long repeatRiders,
        long driversOperatingUnverified,
        long graceExpiringSoon,
        List<TopRouteItem> topRoutes
) {
    public record TopRouteItem(String origin, String destination, long seats, BigDecimal grossFare) {
    }
}
