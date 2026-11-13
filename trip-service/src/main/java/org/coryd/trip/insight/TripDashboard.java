package org.coryd.trip.insight;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * trip-service's part of GET /ops/dashboard (docs/ops-console/dashboard-reports.md). Trips posted/completed/cancelled count
 * routes created in the range - the daily email's definitions, so the two agree. Rates are percentages (one decimal), null
 * when there's nothing to divide by.
 */
public record TripDashboard(LocalDate from, LocalDate to, String timezone, Trips trips, Seats seats, Drivers drivers, Riders riders,
                            Bookings bookings, Map<String, Long> cancellationsBySource, Map<String, Long> cancellationsByKind,
                            BigDecimal grossFaresCompleted, List<Day> daily, List<Activity> recentActivity) {

    public record Trips(long posted, long completed, long cancelled, long inProgressNow, long stuckNow, Double completionRate,
                        Double cancellationRate) {
    }

    /** offered: seats on trips posted in the range. booked: seats on bookings made in the range. */
    public record Seats(long offered, long booked, Double bookingRate, Double avgSeatsFilledPerCompletedTrip) {
    }

    /** activeInRange: posted a trip in the range. onTripNow: a trip in progress. onlineNow: on a trip, or a trip published to leave within 2 hours either side of now. */
    public record Drivers(long activeInRange, long onTripNow, long onlineNow) {
    }

    public record Riders(long activeInRange, long repeatInRange) {
    }

    /** The activity breakdown - bookings made in the range (payment-pending holds excluded), by where they ended up. */
    public record Bookings(long made, long confirmedOrUnderway, long completed, long cancelledByRider, long cancelledByDriver, long noShows) {
    }

    public record Day(LocalDate date, long tripsPosted, long tripsCompleted, long tripsCancelled, long bookings, long seatsBooked,
                      long cancellations, BigDecimal grossFaresCompleted) {
    }

    /** The feed: ops actions (audit log) and platform events (trips posted, SOS raised, cancellations), newest first. */
    public record Activity(Instant at, String kind, String summary, String actorId, String actorName, String targetType, String targetId) {
    }
}
