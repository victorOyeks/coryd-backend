package org.coryd.notification.report;

import org.coryd.notification.client.PlatformStatsClient.IdentityStats;
import org.coryd.notification.client.PlatformStatsClient.PaymentStats;
import org.coryd.notification.client.PlatformStatsClient.ProfileStats;
import org.coryd.notification.client.PlatformStatsClient.TripStats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record ReportData(
        ReportPeriod period,
        ReportRange range,
        TripStats trip,
        PaymentStats payment,
        IdentityStats identity,
        ProfileStats profile,
        TripStats previousTrip,
        PaymentStats previousPayment,
        List<Bucket> breakdown,
        List<Bucket> trend
) {

    /** One bar in the day-by-day, week-by-week or six-month chart. */
    public record Bucket(String label, BigDecimal grossFares) {
    }

    public BigDecimal seatFillRate() {
        return percentage(BigDecimal.valueOf(trip.seatsBooked()), BigDecimal.valueOf(trip.seatsOffered()));
    }

    public BigDecimal completionRate() {
        long attempted = trip.bookingsCompleted() + trip.bookingsCancelledRider()
                + trip.bookingsCancelledDriver() + trip.noShows();
        return percentage(BigDecimal.valueOf(trip.bookingsCompleted()), BigDecimal.valueOf(attempted));
    }

    public BigDecimal repeatRiderRate() {
        return percentage(BigDecimal.valueOf(trip.repeatRiders()), BigDecimal.valueOf(trip.distinctRiders()));
    }

    public BigDecimal averageFare() {
        if (trip.seatsBooked() == 0) {
            return BigDecimal.ZERO;
        }
        return payment.grossFares().divide(BigDecimal.valueOf(trip.seatsBooked()), 0, RoundingMode.HALF_UP);
    }

    /** Null when there is no prior period to compare against, so the template can omit the delta. */
    public BigDecimal grossChangePercent() {
        BigDecimal before = previousPayment.grossFares();
        if (before == null || before.signum() == 0) {
            return null;
        }
        return payment.grossFares().subtract(before)
                .multiply(BigDecimal.valueOf(100))
                .divide(before, 1, RoundingMode.HALF_UP);
    }

    public long seatsChange() {
        return trip.seatsBooked() - previousTrip.seatsBooked();
    }

    public long routesChange() {
        return trip.routesPosted() - previousTrip.routesPosted();
    }

    private static BigDecimal percentage(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }
}
