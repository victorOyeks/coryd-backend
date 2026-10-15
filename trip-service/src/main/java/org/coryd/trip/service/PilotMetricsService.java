package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.dto.PilotMetricsResponse;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.RouteSearch;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteSearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pilot health for the Ops Console: do searches find rides, do riders who search go on to book, do
 * seats fill, do riders come back, does notify-me turn into bookings.
 * Worked out in memory from the window's rows, which is fine at pilot volume.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PilotMetricsService {

    /** Routes that actually ran or could still run - a cancelled route offered no seats. */
    private static final Set<RouteStatus> OFFERED = EnumSet.of(RouteStatus.PUBLISHED, RouteStatus.FULL, RouteStatus.IN_PROGRESS, RouteStatus.COMPLETED);
    private static final Set<BookingStatus> TOOK_A_SEAT = EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP,
            BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED, BookingStatus.NO_SHOW);

    private final RouteSearchRepository routeSearchRepository;
    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final NotifyMeRequestRepository notifyMeRequestRepository;

    public PilotMetricsResponse metrics(int days) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(days));

        List<RouteSearch> searches = routeSearchRepository.findByCreatedAtAfter(from);
        List<RoutePost> routes = routePostRepository.findByStatusInAndDepartureTimeBetween(OFFERED, from, to);
        List<Booking> seats = routes.isEmpty() ? List.of() : bookingRepository.findByRoutePostIdIn(routes.stream().map(RoutePost::getId).toList())
                .stream().filter(b -> TOOK_A_SEAT.contains(b.getStatus())).toList();

        return new PilotMetricsResponse(from, to, totals(searches, routes, seats),
                weekTwoRepeatRate(from, to), notifyMeRequestRepository.countByCreatedAtAfter(from),
                notifyMeRequestRepository.countBookedSince(from), topSearchedRoutes(searches));
    }

    private static PilotMetricsResponse.Totals totals(List<RouteSearch> searches, List<RoutePost> routes, List<Booking> seats) {
        long withResults = searches.stream().filter(s -> s.getResultCount() != null && s.getResultCount() > 0).count();
        Set<String> searchers = searches.stream().map(RouteSearch::getRiderId).collect(Collectors.toSet());
        long booked = seats.stream().map(Booking::getRiderId).filter(searchers::contains).distinct().count();
        long offered = routes.stream().mapToLong(r -> r.getSeatCapacity() == null ? 0 : r.getSeatCapacity()).sum();
        long taken = seats.stream().mapToLong(b -> b.getSeatCount() == null ? 0 : b.getSeatCount()).sum();
        return new PilotMetricsResponse.Totals(searches.size(), withResults, rate(withResults, searches.size()),
                searchers.size(), booked, rate(booked, searchers.size()), routes.size(), offered, taken, rate(taken, offered));
    }

    private Double weekTwoRepeatRate(Instant from, Instant to) {
        Set<String> lastWeek = completedRiders(from.minus(Duration.ofDays(7)), from);
        if (lastWeek.isEmpty()) {
            return null;
        }
        Set<String> thisWindow = completedRiders(from, to);
        long back = lastWeek.stream().filter(thisWindow::contains).count();
        return rate(back, lastWeek.size());
    }

    private Set<String> completedRiders(Instant from, Instant to) {
        List<String> routeIds = routePostRepository.findByStatusInAndDepartureTimeBetween(List.of(RouteStatus.COMPLETED), from, to)
                .stream().map(RoutePost::getId).toList();
        return routeIds.isEmpty() ? Set.of() : bookingRepository.findByRoutePostIdIn(routeIds).stream()
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED).map(Booking::getRiderId).collect(Collectors.toSet());
    }

    private static List<PilotMetricsResponse.SearchedRoute> topSearchedRoutes(List<RouteSearch> searches) {
        return searches.stream()
                .collect(Collectors.groupingBy(s -> label(s.getOriginLabel()) + "\u0000" + label(s.getDestinationLabel())))
                .values().stream()
                .map(group -> new PilotMetricsResponse.SearchedRoute(
                        group.getFirst().getOriginLabel(), group.getFirst().getDestinationLabel(), group.size(),
                        group.stream().map(RouteSearch::getRiderId).distinct().count(),
                        group.stream().filter(s -> s.getResultCount() == null || s.getResultCount() == 0).count()))
                .sorted(Comparator.comparingLong(PilotMetricsResponse.SearchedRoute::riders).reversed()
                        .thenComparing(Comparator.comparingLong(PilotMetricsResponse.SearchedRoute::searches).reversed()))
                .limit(10)
                .toList();
    }

    private static String label(String value) {
        return value == null ? "(unnamed)" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
