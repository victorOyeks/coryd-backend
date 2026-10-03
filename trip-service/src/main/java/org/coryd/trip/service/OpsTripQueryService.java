package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.LiveTripPhase;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.OpsLiveTripItem;
import org.coryd.trip.dto.OpsTripCountsResponse;
import org.coryd.trip.dto.OpsTripDetailResponse;
import org.coryd.trip.dto.OpsTripItem;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Raw trip data for the Ops Console - served through /internal/ops (ops-service only), which adds the
 * driver, vehicle and rider details from profile-service and identity-service. A "trip" here is a route
 * post: one driver, one vehicle, one departure, every booking on it.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpsTripQueryService {

    private static final List<RouteStatus> ACTIVE = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL, RouteStatus.IN_PROGRESS);
    /** Riders carried or still booked - what seatsBooked and totalFare are made of, same as the driver's receipt. */
    private static final List<BookingStatus> CARRIED = List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP,
            BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED);

    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final RatingRepository ratingRepository;
    private final LocationService locationService;

    /** Trips table, newest departure first. A null status is the "All" tab. */
    public PagedResponse<OpsTripItem> trips(OpsTripStatus status, String search, Pageable pageable) {
        Page<RoutePost> routes = routePostRepository.searchForOps(statusesOf(status), term(search), pageable);
        return PagedResponse.from(new PageImpl<>(items(routes.getContent()), pageable, routes.getTotalElements()));
    }

    /** The tab badges - they follow the same search as the table. */
    public OpsTripCountsResponse counts(String search) {
        String term = term(search);
        return new OpsTripCountsResponse(
                routePostRepository.countForOps(statusesOf(null), term),
                routePostRepository.countForOps(statusesOf(OpsTripStatus.ACTIVE), term),
                routePostRepository.countForOps(statusesOf(OpsTripStatus.COMPLETED), term),
                routePostRepository.countForOps(statusesOf(OpsTripStatus.CANCELLED), term));
    }

    public OpsTripDetailResponse trip(String routePostId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));
        OpsTripItem item = items(List.of(route)).getFirst();
        // Every booking that was ever paid for - cancellations and no-shows included, so ops sees what happened.
        List<OpsTripDetailResponse.Passenger> passengers = bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> b.getConfirmedAt() != null)
                .sorted(Comparator.comparing(Booking::getConfirmedAt))
                .map(b -> new OpsTripDetailResponse.Passenger(b.getId(), b.getRiderId(), b.getSeatCount(), b.getFareTotal(), b.getStatus(),
                        b.getPickupLabel(), b.getPickupLat(), b.getPickupLng(), b.getConfirmedAt(), b.getPickupConfirmedAt()))
                .toList();
        return new OpsTripDetailResponse(item, route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng(),
                route.getRouteNote(), route.getEstimatedDurationMinutes(),
                route.getStatus() == RouteStatus.IN_PROGRESS ? locationService.peekRouteLocation(routePostId) : null,
                passengers);
    }

    /** Started trips plus those inside the 30-minute boarding window, soonest departure first. */
    public List<OpsLiveTripItem> live(String search) {
        Instant now = Instant.now();
        String term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<RoutePost> routes = routePostRepository.findLiveForOps(now.plus(TripScheduleService.ACTIVE_WINDOW)).stream()
                .filter(r -> term == null || matches(r, term))
                .toList();
        if (routes.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> seats = seatsBooked(routes);
        Map<String, RatingRepository.RatingSummary> ratings = ratings(routes);

        return routes.stream().map(r -> {
            boolean started = r.getStatus() == RouteStatus.IN_PROGRESS;
            int duration = r.getEstimatedDurationMinutes() == null
                    ? TripScheduleService.DEFAULT_DURATION_MINUTES : r.getEstimatedDurationMinutes();
            Instant start = started && r.getStartedAt() != null ? r.getStartedAt() : r.getDepartureTime();
            long elapsed = started ? Math.max(0, Duration.between(start, now).toMinutes()) : 0;
            double progress = started ? Math.min(1.0, elapsed / (double) Math.max(duration, 1)) : 0.0;
            RatingRepository.RatingSummary rating = ratings.get(r.getDriverId());
            return new OpsLiveTripItem(r.getId(), r.getDriverId(), r.getOriginLabel(), r.getDestinationLabel(),
                    r.getOriginLat(), r.getOriginLng(), r.getDestinationLat(), r.getDestinationLng(),
                    r.getDepartureTime(), r.getStartedAt(),
                    started ? LiveTripPhase.ACTIVE : LiveTripPhase.BOARDING,
                    r.getSeatCapacity(), seats.getOrDefault(r.getId(), 0), duration, elapsed,
                    start.plus(Duration.ofMinutes(duration)), progress,
                    rating == null ? null : rating.getAvgStars(), rating == null ? 0 : rating.getRatingCount(),
                    started ? locationService.peekRouteLocation(r.getId()) : null);
        }).toList();
    }

    private List<OpsTripItem> items(List<RoutePost> routes) {
        if (routes.isEmpty()) {
            return List.of();
        }
        Map<String, List<Booking>> carried = bookingRepository.findByRoutePostIdIn(routes.stream().map(RoutePost::getId).toList()).stream()
                .filter(b -> CARRIED.contains(b.getStatus()))
                .collect(Collectors.groupingBy(Booking::getRoutePostId));
        Map<String, RatingRepository.RatingSummary> ratings = ratings(routes);

        return routes.stream().map(r -> {
            List<Booking> booked = carried.getOrDefault(r.getId(), List.of());
            RatingRepository.RatingSummary rating = ratings.get(r.getDriverId());
            return new OpsTripItem(r.getId(), r.getDriverId(), r.getVehicleId(), r.getOriginLabel(), r.getDestinationLabel(),
                    r.getDepartureTime(), r.getStatus(), r.getSeatCapacity(), r.getSeatsAvailable(),
                    booked.stream().mapToInt(Booking::getSeatCount).sum(), r.getFarePerSeat(),
                    booked.stream().map(Booking::getFareTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                    rating == null ? null : rating.getAvgStars(), rating == null ? 0 : rating.getRatingCount(),
                    r.getStartedAt(), r.getCompletedAt());
        }).toList();
    }

    private Map<String, Integer> seatsBooked(List<RoutePost> routes) {
        return bookingRepository.findByRoutePostIdIn(routes.stream().map(RoutePost::getId).toList()).stream()
                .filter(b -> CARRIED.contains(b.getStatus()))
                .collect(Collectors.groupingBy(Booking::getRoutePostId, Collectors.summingInt(Booking::getSeatCount)));
    }

    private Map<String, RatingRepository.RatingSummary> ratings(List<RoutePost> routes) {
        List<String> driverIds = routes.stream().map(RoutePost::getDriverId).distinct().toList();
        return ratingRepository.summarize(driverIds).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, Function.identity()));
    }

    private static boolean matches(RoutePost route, String term) {
        return route.getId().toLowerCase(Locale.ROOT).startsWith(term)
                || route.getOriginLabel().toLowerCase(Locale.ROOT).contains(term)
                || route.getDestinationLabel().toLowerCase(Locale.ROOT).contains(term);
    }

    private static List<RouteStatus> statusesOf(OpsTripStatus status) {
        if (status == null) {
            return List.of(RouteStatus.values());
        }
        return switch (status) {
            case ACTIVE -> ACTIVE;
            case COMPLETED -> List.of(RouteStatus.COMPLETED);
            case CANCELLED -> List.of(RouteStatus.CANCELLED);
        };
    }

    private static String term(String search) {
        return search == null || search.isBlank() ? null : search.trim();
    }
}
