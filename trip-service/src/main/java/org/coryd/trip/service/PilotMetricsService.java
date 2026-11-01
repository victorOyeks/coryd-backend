package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.dto.PilotMetricsResponse;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.repository.PilotMetricsRepository;
import org.coryd.trip.repository.RouteTimeChangeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Pilot health for the Ops Console: do searches find rides, do riders who search go on to book, do
 * seats fill, do riders come back, does notify-me turn into bookings, and how leaving now is doing.
 * Every number is an aggregate query - the window's rows never leave the database.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PilotMetricsService {

    /** Routes that actually ran or could still run - a cancelled route offered no seats. */
    private static final Set<RouteStatus> OFFERED = EnumSet.of(RouteStatus.PUBLISHED, RouteStatus.FULL, RouteStatus.IN_PROGRESS, RouteStatus.COMPLETED);
    private static final Set<BookingStatus> TOOK_A_SEAT = EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP,
            BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED, BookingStatus.NO_SHOW);

    private final PilotMetricsRepository pilotMetricsRepository;
    private final NotifyMeRequestRepository notifyMeRequestRepository;
    private final RouteTimeChangeRepository routeTimeChangeRepository;

    public PilotMetricsResponse metrics(int days) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(days));
        List<String> routeStatuses = OFFERED.stream().map(Enum::name).toList();
        List<String> seatStatuses = TOOK_A_SEAT.stream().map(Enum::name).toList();

        PilotMetricsRepository.SearchTotals searches = pilotMetricsRepository.searchTotals(from);
        PilotMetricsRepository.OfferedTotals offered = pilotMetricsRepository.offeredTotals(from, to, routeStatuses, seatStatuses);
        long booked = pilotMetricsRepository.ridersWhoSearchedAndBooked(from, to, routeStatuses, seatStatuses);
        PilotMetricsResponse.Totals totals = new PilotMetricsResponse.Totals(searches.getSearches(), searches.getWithResults(),
                rate(searches.getWithResults(), searches.getSearches()), searches.getRiders(), booked, rate(booked, searches.getRiders()),
                offered.getRoutes(), offered.getSeatsOffered(), offered.getSeatsBooked(), rate(offered.getSeatsBooked(), offered.getSeatsOffered()));

        PilotMetricsRepository.Repeat repeat = pilotMetricsRepository.repeat(from.minus(Duration.ofDays(7)), from, to);
        List<PilotMetricsResponse.SearchedRoute> topRoutes = pilotMetricsRepository.topSearchedRoutes(from).stream()
                .map(r -> new PilotMetricsResponse.SearchedRoute(r.getOriginLabel(), r.getDestinationLabel(), r.getSearches(), r.getRiders(), r.getNoResults()))
                .toList();

        return new PilotMetricsResponse(from, to, totals, repeat.getLastWeek() == 0 ? null : rate(repeat.getBack(), repeat.getLastWeek()),
                notifyMeRequestRepository.countByCreatedAtAfter(from), notifyMeRequestRepository.countBookedSince(from), topRoutes,
                leavingNow(from, to, offered));
    }

    private PilotMetricsResponse.LeavingNow leavingNow(Instant from, Instant to, PilotMetricsRepository.OfferedTotals offered) {
        PilotMetricsRepository.Waits waits = pilotMetricsRepository.waits(from, to);
        List<PilotMetricsResponse.UnmetArea> unmet = pilotMetricsRepository.unmetAreas(from, to, Instant.now()).stream()
                .map(a -> new PilotMetricsResponse.UnmetArea(a.getDestinationLabel(), a.getRiders()))
                .toList();
        return new PilotMetricsResponse.LeavingNow(offered.getLeavingNowRoutes(), offered.getLeavingNowSeats(), waits.getWaited(), waits.getFound(),
                rate(waits.getFound(), waits.getWaited()), unmet, routeTimeChangeRepository.countByCreatedAtAfter(from),
                routeTimeChangeRepository.countByCreatedAtAfterAndLastMinuteTrue(from));
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
