package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.trip.dto.OpsDriverStats;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The Drivers Directory's per-driver numbers, for many drivers in four queries - the same figures the driver's own dashboard shows. */
@Service
@RequiredArgsConstructor
public class OpsDriverStatsService {

    private final BookingRepository bookingRepository;
    private final RatingRepository ratingRepository;

    @Transactional(readOnly = true)
    public List<OpsDriverStats> stats(Collection<String> driverIds) {
        List<String> ids = driverIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, BookingRepository.DriverTripCountsById> trips = bookingRepository.countLifetimeTripsForDrivers(ids).stream()
                .collect(Collectors.toMap(BookingRepository.DriverTripCountsById::getDriverId, Function.identity()));
        Map<String, RatingRepository.RatingSummary> ratings = ratingRepository.summarize(ids).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, Function.identity()));
        Map<String, Long> reports = bookingRepository.countReportsAgainstDrivers(ids).stream()
                .collect(Collectors.toMap(BookingRepository.DriverReportCount::getDriverId, BookingRepository.DriverReportCount::getReports));
        Map<String, Instant> lastTrips = OpsRiderStatsService.lastTrips(bookingRepository.lastTripsForDrivers(ids));
        return ids.stream().map(id -> {
            BookingRepository.DriverTripCountsById t = trips.get(id);
            long completed = t == null || t.getCompleted() == null ? 0 : t.getCompleted();
            long cancelled = t == null || t.getCancelledDriver() == null ? 0 : t.getCancelledDriver();
            Integer reliability = completed + cancelled == 0 ? null : (int) Math.round(completed * 100.0 / (completed + cancelled));
            RatingRepository.RatingSummary r = ratings.get(id);
            return new OpsDriverStats(id, completed, r == null ? null : r.getAvgStars(), r == null || r.getRatingCount() == null ? 0 : r.getRatingCount(),
                    reliability, reports.getOrDefault(id, 0L), lastTrips.get(id));
        }).toList();
    }
}
