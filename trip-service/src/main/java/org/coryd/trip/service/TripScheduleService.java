package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * When a trip counts as active (the app switches into trip mode) versus upcoming, and the rule that
 * keeps one person's trips from overlapping - across both roles, since a dual-role user can't drive a
 * route and ride someone else's at the same time.
 */
@Service
@RequiredArgsConstructor
public class TripScheduleService {

    /** A trip becomes active this long before departure, or as soon as it's under way. */
    public static final Duration ACTIVE_WINDOW = Duration.ofMinutes(30);
    /** Slack after the expected arrival before another trip may start - Lagos traffic. */
    public static final Duration OVERLAP_BUFFER = Duration.ofMinutes(30);
    /** Used when a route has no Google duration on record. */
    public static final int DEFAULT_DURATION_MINUTES = 60;

    /** Routes that still occupy their driver. */
    public static final List<RouteStatus> LIVE_ROUTE_STATUSES = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL, RouteStatus.IN_PROGRESS);
    /** Paid seats that still occupy their rider. HELD and RESERVED don't - a rider has at most one checkout open at a time. */
    public static final List<BookingStatus> SEATED_STATUSES = List.of(
            BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH).withZone(LAGOS);

    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;

    public static boolean isActive(RoutePost route, Instant now) {
        return route.getStatus() == RouteStatus.IN_PROGRESS || !route.getDepartureTime().isAfter(now.plus(ACTIVE_WINDOW));
    }

    public static Instant occupiedUntil(RoutePost route) {
        int minutes = route.getEstimatedDurationMinutes() == null ? DEFAULT_DURATION_MINUTES : route.getEstimatedDurationMinutes();
        return route.getDepartureTime().plus(Duration.ofMinutes(minutes)).plus(OVERLAP_BUFFER);
    }

    public static boolean overlaps(RoutePost a, RoutePost b) {
        return a.getDepartureTime().isBefore(occupiedUntil(b)) && b.getDepartureTime().isBefore(occupiedUntil(a));
    }

    /** Rejects candidate if it overlaps a route this user is driving or a seat they hold. candidate may be
     * a route not saved yet (driver posting) or the route being booked (rider). */
    public void requireNoOverlap(String userId, RoutePost candidate) {
        for (RoutePost own : routePostRepository.findByDriverIdAndStatusIn(userId, LIVE_ROUTE_STATUSES)) {
            if (!own.getId().equals(candidate.getId()) && overlaps(own, candidate)) {
                throw clash("a route you're driving", own);
            }
        }

        List<Booking> seats = bookingRepository.findByRiderIdAndStatusIn(userId, SEATED_STATUSES);
        if (seats.isEmpty()) {
            return;
        }
        Map<String, RoutePost> routes = routePostRepository.findAllById(seats.stream().map(Booking::getRoutePostId).distinct().toList())
                .stream().collect(Collectors.toMap(RoutePost::getId, Function.identity()));
        for (Booking seat : seats) {
            RoutePost booked = routes.get(seat.getRoutePostId());
            if (booked == null) {
                continue;
            }
            if (booked.getId().equals(candidate.getId())) {
                throw new BadRequestException("You've already booked a seat on this ride");
            }
            if (overlaps(booked, candidate)) {
                throw clash("a ride you've booked", booked);
            }
        }
    }

    public static String formatLagos(Instant instant) {
        return WHEN.format(instant);
    }

    private static BadRequestException clash(String what, RoutePost other) {
        return new BadRequestException("This overlaps " + what + " - " + other.getOriginLabel() + " to "
                + other.getDestinationLabel() + " on " + WHEN.format(other.getDepartureTime()) + ". Trips can't overlap.");
    }
}
