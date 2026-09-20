package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.trip.dto.TripStatsResponse;
import org.coryd.trip.config.policy.DriverVerificationPolicy;
import org.coryd.trip.repository.PlatformStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlatformStatsService {

    private static final int TOP_ROUTE_LIMIT = 5;

    private final PlatformStatsRepository platformStatsRepository;
    private final DriverVerificationPolicy verificationPolicy;

    @Transactional(readOnly = true)
    public TripStatsResponse statsFor(Instant from, Instant to) {
        PlatformStatsRepository.RouteCounts routes = platformStatsRepository.routeCounts(from, to);
        PlatformStatsRepository.BookingCounts bookings = platformStatsRepository.bookingCounts(from, to);
        PlatformStatsRepository.RiderCounts riders = platformStatsRepository.riderCounts(from, to);
        PlatformStatsRepository.UnverifiedDriverCounts unverified =
                platformStatsRepository.unverifiedDriverCounts(verificationPolicy.getGracePeriodDays());

        List<TripStatsResponse.TopRouteItem> topRoutes = platformStatsRepository.topRoutes(from, to, TOP_ROUTE_LIMIT)
                .stream()
                .map(r -> new TripStatsResponse.TopRouteItem(
                        r.getOriginLabel(),
                        r.getDestinationLabel(),
                        r.getSeats(),
                        r.getGrossFare() == null ? BigDecimal.ZERO : r.getGrossFare()))
                .toList();

        return new TripStatsResponse(
                routes.getPosted(),
                routes.getCompleted(),
                routes.getCancelled(),
                zeroIfNull(routes.getSeatsOffered()),
                routes.getActiveDrivers(),
                bookings.getConfirmed(),
                bookings.getCompleted(),
                bookings.getCancelledRider(),
                bookings.getCancelledDriver(),
                bookings.getNoShows(),
                zeroIfNull(bookings.getSeatsBooked()),
                riders.getDistinctRiders(),
                riders.getRepeatRiders(),
                unverified.getOperatingUnverified(),
                unverified.getGraceExpiringSoon(),
                topRoutes);
    }

    private static long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }
}
