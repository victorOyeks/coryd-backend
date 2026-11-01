package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.RatingDirection;
import org.coryd.trip.dto.OpsRiderStats;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The Riders Directory's per-rider numbers, for many riders in three queries. */
@Service
@RequiredArgsConstructor
public class OpsRiderStatsService {

    private final BookingRepository bookingRepository;
    private final RatingRepository ratingRepository;

    @Transactional(readOnly = true)
    public List<OpsRiderStats> stats(Collection<String> riderIds) {
        List<String> ids = riderIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, BookingRepository.RiderTripCounts> trips = bookingRepository.countTripsForRiders(ids).stream()
                .collect(Collectors.toMap(BookingRepository.RiderTripCounts::getRiderId, Function.identity()));
        Map<String, RatingRepository.RatingSummary> ratings = ratingRepository.summarize(ids, RatingDirection.DRIVER_TO_RIDER).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, Function.identity()));
        Map<String, Long> reports = bookingRepository.countReportsAgainstRiders(ids).stream()
                .collect(Collectors.toMap(BookingRepository.RiderReportCount::getRiderId, BookingRepository.RiderReportCount::getReports));
        return ids.stream().map(id -> {
            BookingRepository.RiderTripCounts t = trips.get(id);
            long completed = t == null || t.getCompleted() == null ? 0 : t.getCompleted();
            long missed = t == null ? 0 : (t.getCancelledRider() == null ? 0 : t.getCancelledRider()) + (t.getNoShow() == null ? 0 : t.getNoShow());
            Integer reliability = completed + missed == 0 ? null : (int) Math.round(completed * 100.0 / (completed + missed));
            RatingRepository.RatingSummary r = ratings.get(id);
            return new OpsRiderStats(id, completed, r == null ? null : r.getAvgStars(), r == null || r.getRatingCount() == null ? 0 : r.getRatingCount(),
                    reliability, reports.getOrDefault(id, 0L), t == null || t.getSpent() == null ? BigDecimal.ZERO : t.getSpent());
        }).toList();
    }
}
