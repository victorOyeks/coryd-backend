package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.RouteCancelledEvent;
import org.coryd.common.kafka.events.RoutePublishedEvent;
import org.coryd.common.kafka.events.SeatReservedEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.client.PaymentServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.GeoCapabilities;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.config.policy.DriverVerificationPolicy;
import org.coryd.trip.config.policy.IdentityVerificationPolicy;
import org.coryd.trip.dto.DirectionsMultiStopResult;
import org.coryd.trip.dto.DistanceMatrixResult;
import org.coryd.trip.dto.DriverPostingEligibilityResponse;
import org.coryd.trip.dto.MultiStopRoutePreview;
import org.coryd.trip.dto.NearbyActivityResponse;
import org.coryd.trip.dto.NearbyRidePin;
import org.coryd.trip.dto.PostRouteRequest;
import org.coryd.trip.dto.RideDetailsResponse;
import org.coryd.trip.dto.RideSearchResult;
import org.coryd.trip.dto.RideStop;
import org.coryd.trip.dto.RoutePreview;
import org.coryd.trip.dto.RouteSummary;
import org.coryd.trip.dto.SearchRequest;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.DriverVerificationCache;
import org.coryd.trip.entity.FareReport;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SuggestedFareRange;
import org.coryd.trip.event.RouteSearchRelevantChangeEvent;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.DriverVerificationCacheRepository;
import org.coryd.trip.repository.FareReportRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.IdentityVerificationCacheRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SuspendedUserRepository;
import org.coryd.trip.repository.SuggestedFareRangeRepository;
import org.coryd.trip.util.GeoMath;
import org.coryd.trip.util.PolylineDecoder;
import org.coryd.trip.util.PolylineMath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService {

    /** How long a driver-initiated seat reservation waits for the favourited rider to respond -
     * deliberately longer than BookingService.SEAT_HOLD_MINUTES (4), since this is "decide if you
     * even want this seat," not "finish paying for a seat you already agreed to." */
    public static final int RESERVATION_HOLD_MINUTES = 15;

    private static final int NEARBY_OPTIONS_LIMIT = 5;
    private static final double NEARBY_OPTIONS_RADIUS_MULTIPLIER = 3.0;
    private static final int NEARBY_OPTIONS_TIME_WINDOW_HOURS = 24;

    /** Google's Directions API caps waypoints at 25 (23 here, leaving headroom - origin/destination
     * are separate params, not counted against this, but keeping a margin against any per-account
     * quota difference is safer than pushing the exact documented ceiling). */
    private static final int MAX_MULTI_STOP_WAYPOINTS = 23;

    /** Bookings still "on" this trip's manifest - a stop the multi-stop route line should pass
     * through. Excludes HELD (not yet confirmed, may never be) and both cancelled statuses;
     * includes IN_PROGRESS since that rider's pickup point is still part of the planned route
     * shape even after they've boarded. */
    private static final List<BookingStatus> MANIFEST_STOP_STATUSES = List.of(
            BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);

    private final RoutePostRepository routePostRepository;
    private final FareReportRepository fareReportRepository;
    private final SuggestedFareRangeRepository suggestedFareRangeRepository;
    private final NotifyMeService notifyMeService;
    private final DriverStatsPublisher driverStatsPublisher;
    private final DemandService demandService;
    private final BookingRepository bookingRepository;
    private final DriverVerificationCacheRepository driverVerificationCacheRepository;
    private final SuspendedUserRepository suspendedUserRepository;
    private final UserBlockService userBlockService;
    private final IdentityVerificationCacheRepository identityVerificationCacheRepository;
    private final IdentityVerificationPolicy identityVerificationPolicy;
    private final RatingRepository ratingRepository;
    private final ProfileServiceClient profileServiceClient;
    private final PaymentServiceClient paymentServiceClient;
    private final MapsService mapsService;
    private final MatchingProperties matchingProperties;
    private final DriverVerificationPolicy verificationPolicy;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ChatService chatService;
    private final LiveSearchSessionRegistry liveSearchSessionRegistry;
    private final RouteMatcher routeMatcher;
    private final GeoCapabilities geoCapabilities;
    private final ApplicationEventPublisher eventPublisher;
    private final TripScheduleService tripScheduleService;
    private final CancellationLogService cancellationLogService;

    /** cancelledBy for a route auto-cancelled because its driver never started it. */
    public static final String CANCELLED_NOT_STARTED = "NOT_STARTED";

    /** A "leave now" post can land a few seconds after the departure the app filled in. */
    private static final Duration DEPARTURE_PAST_TOLERANCE = Duration.ofMinutes(5);

    /** Must stay inside the payout settlement cycle: a trip that departs after the driver has been
     * settled leaves a refund with nothing to claw back. */
    @Value("${trip.max-days-ahead:3}")
    private int maxDaysAhead;

    @Transactional
    public RoutePost post(String driverId, String callerId, PostRouteRequest request) {
        return post(driverId, callerId, request, null);
    }

    /** routeTemplateId marks a trip posted from a driver's regular route - every check is the same as by hand. */
    @Transactional
    public RoutePost post(String driverId, String callerId, PostRouteRequest request, String routeTemplateId) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can post their own route");
        }
        if (!request.departureTime().isAfter(Instant.now().minus(DEPARTURE_PAST_TOLERANCE))) {
            throw new BadRequestException("Departure time has already passed");
        }
        if (request.departureTime().isAfter(Instant.now().plus(Duration.ofDays(maxDaysAhead)))) {
            throw new BadRequestException("Routes can only be posted up to " + maxDaysAhead + " days ahead");
        }
        if (suspendedUserRepository.existsById(driverId)) {
            throw new BadRequestException("This account is suspended. Contact CoRyd support.");
        }
        if (identityVerificationPolicy.blocksUnverified() && !driverIdentityVerified(driverId)) {
            throw new BadRequestException("Verify your identity to post a route");
        }
        requirePostingEligibility(driverId);
        requirePaymentStanding(driverId);
        bookingRepository.lockUser(driverId);

        if (request.vehicleId() != null) {
            ProfileServiceClient.VehicleSummaryItem vehicle = profileServiceClient
                    .fetchVehicleSummaries(List.of(request.vehicleId()))
                    .get(request.vehicleId());
            if (vehicle != null && request.seatCapacity() > vehicle.seatCapacity()) {
                throw new BadRequestException("Seats offered can't exceed this vehicle's registered capacity (" + vehicle.seatCapacity() + ")");
            }
        }

        List<PostRouteRequest.ReservationRequest> reservations = request.reservations() == null ? List.of() : request.reservations();
        if (!reservations.isEmpty()) {
            long distinctRiders = reservations.stream().map(PostRouteRequest.ReservationRequest::riderId).distinct().count();
            if (distinctRiders != reservations.size()) {
                throw new BadRequestException("Each favourite can only be reserved a seat once per route");
            }
            int requestedSeats = reservations.stream().mapToInt(PostRouteRequest.ReservationRequest::seatCount).sum();
            if (requestedSeats > request.seatCapacity()) {
                throw new BadRequestException("Reserved seats (" + requestedSeats + ") can't exceed this route's seat capacity (" + request.seatCapacity() + ")");
            }
             List<String> favoritedBy = profileServiceClient.fetchFavoritedBy(driverId);
            for (PostRouteRequest.ReservationRequest reservation : reservations) {
                if (!favoritedBy.contains(reservation.riderId())) {
                    throw new BadRequestException("Rider " + reservation.riderId() + " hasn't favourited you - can't reserve a seat for them");
                }
            }
        }

        RoutePreview preview = previewFor(request);
        String road = request.routePolyline() == null || request.routePolyline().isBlank() ? null : request.routePolyline();
        requireRoadBetween(road, request.viaSummary(), request.originLat(), request.originLng(), request.destinationLat(), request.destinationLng());
        Integer duration = preview == null || preview.durationMinutes() <= 0 ? null : preview.durationMinutes();
        if (road != null && duration != null) {
            duration = scaledDuration(preview, road);
        }
        RoutePost route = RoutePost.builder()
                .estimatedDurationMinutes(duration)
                .routePolyline(road != null ? road : preview == null ? null : preview.overviewPolyline())
                .viaSummary(road == null ? null : request.viaSummary().trim())
                .driverId(driverId)
                .vehicleId(request.vehicleId())
                .originLabel(request.originLabel())
                .originLat(request.originLat())
                .originLng(request.originLng())
                .destinationLabel(request.destinationLabel())
                .destinationLat(request.destinationLat())
                .destinationLng(request.destinationLng())
                .departureTime(request.departureTime())
                .seatCapacity(request.seatCapacity())
                .seatsAvailable(request.seatCapacity())
                .farePerSeat(request.farePerSeat())
                .status(RouteStatus.PUBLISHED)
                .routeNote(request.routeNote())
                .routeTemplateId(routeTemplateId)
                .build();
        tripScheduleService.requireNoOverlap(driverId, route);
        routePostRepository.save(route);

        fareReportRepository.save(FareReport.builder()
                .corridorKey(corridorKey(request.originLabel(), request.destinationLabel()))
                .farePerSeat(request.farePerSeat())
                .build());

        if (!reservations.isEmpty()) {
            createReservations(route, reservations);
            // createReservations() reduces seatsAvailable (and possibly flips status to FULL) via
            // bulk @Modifying queries, which bypass the persistence context - refresh the in-memory
            // fields before they're used below, both for the ROUTE_PUBLISHED event/waitlist match
            // (so a waitlisted rider is never told about seats that were already reserved away) and
            // for the route object returned to the driver who just posted it.
            int reservedSeats = reservations.stream().mapToInt(PostRouteRequest.ReservationRequest::seatCount).sum();
            route.setSeatsAvailable(route.getSeatsAvailable() - reservedSeats);
            if (route.getSeatsAvailable() <= 0) {
                route.setStatus(RouteStatus.FULL);
            }
        }

        kafkaTemplate.send(KafkaTopics.ROUTE_PUBLISHED, RoutePublishedEvent.builder()
                .routePostId(route.getId())
                .driverId(driverId)
                .originLabel(route.getOriginLabel())
                .destinationLabel(route.getDestinationLabel())
                .departureTime(LocalDateTime.ofInstant(route.getDepartureTime(), java.time.ZoneOffset.UTC))
                .seatsAvailable(route.getSeatsAvailable())
                .build());

        notifyMatchingWaitlist(route);
        eventPublisher.publishEvent(new RouteSearchRelevantChangeEvent(route.getId()));

        return route;
    }

    /** How far the driver's picked road may start or end from the route's own ends - Google starts a road at the
     * nearest street, which can be a few hundred metres from a pin dropped inside an estate. */
    private static final double ROAD_END_TOLERANCE_KM = 1.5;

    /** A road from /maps/route-options must actually run between this route's ends - otherwise riders would be
     * matched to a line the car never drives. Null (Google's usual road) always passes. */
    public void requireRoadBetween(String polyline, String viaSummary, double originLat, double originLng, double destLat, double destLng) {
        if (polyline == null || polyline.isBlank()) {
            return;
        }
        if (viaSummary == null || viaSummary.isBlank()) {
            throw new BadRequestException("Send viaSummary with the road you picked - riders see it on the ride");
        }
        List<PolylineDecoder.LatLng> points;
        try {
            points = PolylineDecoder.decode(polyline);
        } catch (RuntimeException e) {
            throw new BadRequestException("The road you picked couldn't be read - pick it again");
        }
        if (points.size() < 2) {
            throw new BadRequestException("The road you picked couldn't be read - pick it again");
        }
        PolylineDecoder.LatLng first = points.getFirst();
        PolylineDecoder.LatLng last = points.getLast();
        if (GeoMath.distanceKm(first.lat(), first.lng(), originLat, originLng) > ROAD_END_TOLERANCE_KM
                || GeoMath.distanceKm(last.lat(), last.lng(), destLat, destLng) > ROAD_END_TOLERANCE_KM) {
            throw new BadRequestException("The road you picked doesn't start and end at this route's pickup and destination - pick it again");
        }
    }

    /** The picked road's driving time: Google's time for its usual road, stretched by how much longer the picked
     * one is. Worked out here so the app never sets the time the overlap and safety checks rely on. */
    private static Integer scaledDuration(RoutePreview usual, String road) {
        if (usual.overviewPolyline() == null || usual.overviewPolyline().isBlank()) {
            return usual.durationMinutes();
        }
        double usualMeters = PolylineMath.lengthMeters(PolylineDecoder.decode(usual.overviewPolyline()));
        double roadMeters = PolylineMath.lengthMeters(PolylineDecoder.decode(road));
        if (usualMeters <= 0 || roadMeters <= 0) {
            return usual.durationMinutes();
        }
        return Math.max(1, (int) Math.round(usual.durationMinutes() * roadMeters / usualMeters));
    }

    /** Driving time for the overlap window - a Maps failure falls back to the default rather than
     * blocking the post. Same cached preview the route screen already uses. */
    /** Driving time and the road line, from one Directions call - null when Maps is unavailable. */
    private RoutePreview previewFor(PostRouteRequest request) {
        try {
            return mapsService.getRoutePreview(request.originLat(), request.originLng(),
                    request.destinationLat(), request.destinationLng());
        } catch (Exception e) {
            log.warn("No driving time for new route {} -> {}: {}", request.originLabel(), request.destinationLabel(), e.getMessage());
            return null;
        }
    }

    /** Reserves a seat for each favourite the driver picked while posting this route - the same
     * atomic holdSeats() call reserveSeats() uses, just triggered by the driver instead of the
     * rider, and with a 15-minute window instead of the normal 4-minute one (RESERVED, not HELD -
     * this rider hasn't even agreed to book yet, a materially different wait than "actively
     * paying"). Always succeeds here since post()'s upfront capacity check already guaranteed
     * these seats fit on a brand-new route nothing else could have booked into yet. */
    private void createReservations(RoutePost route, List<PostRouteRequest.ReservationRequest> reservations) {
        String driverName = profileServiceClient.fetchDriverName(route.getDriverId());
        String vehiclePlate = profileServiceClient.fetchVehiclePlate(route.getVehicleId());
        String routeSummary = route.getOriginLabel() + " -> " + route.getDestinationLabel();
        Instant reservationExpiresAt = Instant.now().plus(RESERVATION_HOLD_MINUTES, ChronoUnit.MINUTES);

        for (PostRouteRequest.ReservationRequest reservation : reservations) {
            // Checked, not assumed - if this ever returns 0 despite the upfront sum check
            // (a future refactor reordering things, say), failing loudly beats silently
            // creating a RESERVED booking with no real seat behind it.
            if (routePostRepository.holdSeats(route.getId(), reservation.seatCount()) == 0) {
                throw new IllegalStateException("Failed to hold " + reservation.seatCount()
                        + " seat(s) for reservation on route " + route.getId() + " despite upfront capacity check");
            }

            Booking booking = bookingRepository.save(Booking.builder()
                    .routePostId(route.getId())
                    .riderId(reservation.riderId())
                    .seatCount(reservation.seatCount())
                    .fareTotal(route.getFarePerSeat().multiply(BigDecimal.valueOf(reservation.seatCount())))
                    .status(BookingStatus.RESERVED)
                    .holdExpiresAt(reservationExpiresAt)
                    .driverNameSnapshot(driverName)
                    .vehiclePlateSnapshot(vehiclePlate)
                    .routeSummarySnapshot(routeSummary)
                    .farePerSeatSnapshot(route.getFarePerSeat())
                    .build());

            kafkaTemplate.send(KafkaTopics.SEAT_RESERVED, SeatReservedEvent.builder()
                    .bookingId(booking.getId())
                    .routePostId(route.getId())
                    .riderId(reservation.riderId())
                    .driverId(route.getDriverId())
                    .driverName(driverName)
                    .seatCount(reservation.seatCount())
                    .fareTotal(booking.getFareTotal())
                    .holdExpiresAt(reservationExpiresAt)
                    .build());
        }

        routePostRepository.markFullIfExhausted(route.getId());
    }

    /** Called after a cancellation frees up a seat - markFullIfExhausted has no symmetric
     * counterpart, so without this a route that ever went FULL stayed excluded from search()
     * forever, even once it had a bookable seat again. Also re-runs the same waitlist match a
     * fresh publish gets, since a rider who asked to be notified shouldn't miss a seat opening up
     * on an existing route just because it isn't brand new. */
    @Transactional
    public void reopenIfSeatsAvailable(String routePostId) {
        // Published unconditionally, not just when this flips FULL->PUBLISHED - every seat-release
        // call site (payment failure, hold release, reservation decline, rider cancellation, the
        // expiry sweep) routes through here, and seatsAvailable increases regardless of whether
        // the route had actually gone FULL, which live search results also need to reflect.
        eventPublisher.publishEvent(new RouteSearchRelevantChangeEvent(routePostId));
        if (routePostRepository.reopenIfSeatsAvailable(routePostId) == 0) {
            return;
        }
        routePostRepository.findById(routePostId).ifPresent(notifyMeService::notifySeatOpened);
    }

    private void notifyMatchingWaitlist(RoutePost route) {
        notifyMeService.notifyMatching(route);
    }

    public List<RideSearchResult> search(SearchRequest request) {
        return searchAsRider(request, null);
    }

    /**
     * search() without the drivers this rider is kept apart from (blocked either way). Two kinds of match,
     * merged: routes starting and ending near the rider's ends (as before), and routes whose road passes
     * within a walk of both, in order, reaching the rider's pickup around the time they want - see
     * RouteMatcher. Sorted by when the car reaches the rider.
     */
    public List<RideSearchResult> searchAsRider(SearchRequest request, String riderId) {
        double radiusKm = matchingProperties.getGeoToleranceMeters() / 1000.0;
        int tolerance = matchingProperties.getTimeToleranceMinutes();
        Instant earliest = request.desiredDepartureTime().minus(tolerance, ChronoUnit.MINUTES);
        Instant latest = request.desiredDepartureTime().plus(tolerance, ChronoUnit.MINUTES);

        Map<String, RoutePost> routes = new LinkedHashMap<>();
        routePostRepository.search(earliest, latest, request.originLat(), request.originLng(),
                request.destinationLat(), request.destinationLng(), radiusKm).forEach(r -> routes.put(r.getId(), r));
        Set<String> endToEnd = Set.copyOf(routes.keySet());

        Map<String, RouteMatcher.Match> along = new HashMap<>();
        if (routeMatcher.enabled()) {
            // A route can leave before the window and still reach a rider partway along inside it.
            Instant leavesFrom = earliest.minus(matchingProperties.getMaxTripMinutes(), ChronoUnit.MINUTES);
            List<RoutePost> candidates = geoCapabilities.postgis()
                    ? routePostRepository.findPassingNear(leavesFrom, latest, request.originLat(), request.originLng(),
                            request.destinationLat(), request.destinationLng(), matchingProperties.getWalkMeters())
                    : routePostRepository.findBookableDepartingBetween(leavesFrom, latest);
            for (RoutePost route : candidates) {
                routeMatcher.match(route, request.originLat(), request.originLng(), request.destinationLat(), request.destinationLng())
                        .filter(m -> !m.pickup().time().isBefore(earliest) && !m.pickup().time().isAfter(latest))
                        .ifPresent(m -> {
                            along.put(route.getId(), m);
                            routes.putIfAbsent(route.getId(), route);
                        });
            }
        }

        Instant now = Instant.now();
        List<RoutePost> matched = withoutBlocked(new ArrayList<>(routes.values()), riderId).stream()
                .filter(r -> openForBooking(r, now))
                .sorted(Comparator.comparing((RoutePost r) -> along.containsKey(r.getId()) ? along.get(r.getId()).pickup().time() : r.getDepartureTime()))
                .toList();
        Set<String> partWay = along.keySet().stream().filter(id -> !endToEnd.contains(id)).collect(Collectors.toSet());
        return enrich(matched, request.originLat(), request.originLng(), along, partWay, request.originLabel(), request.destinationLabel());
    }

    private List<RoutePost> withoutBlocked(List<RoutePost> routes, String riderId) {
        Set<String> apart = userBlockService.keptApartFrom(riderId);
        return apart.isEmpty() ? routes : routes.stream().filter(r -> !apart.contains(r.getDriverId())).toList();
    }

    /** Same search, but also registers/refreshes a live-search session so the rider can subscribe
     * to /topic/search-sessions/{sessionId} and get pushed updates instead of polling this endpoint -
     * see LiveSearchPushService/LiveSearchSessionRegistry. sessionId is client-generated (a UUID the
     * app keeps for the life of the search screen); riderId is null-safe only in that a null
     * sessionId skips registration entirely - a real search always has an authenticated caller. */
    public List<RideSearchResult> search(SearchRequest request, String sessionId, String riderId) {
        List<RideSearchResult> results = searchAsRider(request, riderId);
        if (riderId != null) {
            demandService.recordSearch(riderId, request, results.size());
        }
        if (sessionId != null) {
            liveSearchSessionRegistry.registerOrRefresh(sessionId, riderId, request, results);
        }
        return results;
    }

    /**
     * Fallback for the "No rides found" screen - same match shape as search() but with a
     * relaxed radius and a much wider time window, capped to a handful of results. Not a
     * separate query strategy, just wider parameters on the same Haversine search.
     */
    public List<RideSearchResult> getNearbyOptions(SearchRequest request, String riderId) {
        double radiusKm = (matchingProperties.getGeoToleranceMeters() / 1000.0) * NEARBY_OPTIONS_RADIUS_MULTIPLIER;
        Instant earliest = request.desiredDepartureTime();
        Instant latest = request.desiredDepartureTime().plus(NEARBY_OPTIONS_TIME_WINDOW_HOURS, ChronoUnit.HOURS);

        List<RoutePost> matches = routePostRepository.search(
                earliest, latest,
                request.originLat(), request.originLng(),
                request.destinationLat(), request.destinationLng(),
                radiusKm
        );

        Instant now = Instant.now();
        List<RoutePost> capped = withoutBlocked(matches, riderId).stream().filter(r -> openForBooking(r, now))
                .limit(NEARBY_OPTIONS_LIMIT).toList();
        return enrich(capped, request.originLat(), request.originLng());
    }

    /** The Ride Details screen - one enriched route plus its coordinates and road-following route line. */
    public RideDetailsResponse getRouteDetails(String routePostId, double riderLat, double riderLng, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        String unavailableReason = unavailableReason(route, callerId);

        RideSearchResult base = enrich(List.of(route), riderLat, riderLng).getFirst();

        return new RideDetailsResponse(
                base.routePostId(), base.originLabel(), base.destinationLabel(),
                route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng(),
                base.departureTime(),
                base.farePerSeat(), base.seatsAvailable(), base.driverId(), base.driverName(), base.driverPhotoUrl(),
                base.driverVerified(), base.vehicleMake(), base.vehicleModel(), base.vehiclePlate(), base.vehicleHasAC(),
                base.ratingAvg(), base.ratingCount(), base.pickupEtaMinutes(),
                BookingService.SEAT_HOLD_MINUTES, base.routeNote(),
                route.getRoutePolyline() != null ? route.getRoutePolyline()
                        : overviewPolyline(route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng()),
                unavailableReason == null, unavailableReason, route.getViaSummary(),
                base.vehicleColour(), route.getOriginalDepartureTime(), route.getTimeChangeCount(),
                bookableUntil(route)
        );
    }

    /** Still taking bookings: published, seats left, and booking hasn't closed - the driver hasn't started and the grace
     * period after departure hasn't run out. Search shows only these, so whatever a rider finds, they can book. */
    private boolean openForBooking(RoutePost route, Instant now) {
        return route.getStatus() == RouteStatus.PUBLISHED
                && route.getSeatsAvailable() != null && route.getSeatsAvailable() > 0
                && now.isBefore(matchingProperties.bookableUntil(route.getDepartureTime()));
    }

    /** When booking closes, for the app's countdown - null once the trip can't take bookings at all. */
    private java.time.Instant bookableUntil(RoutePost route) {
        return route.getStatus() == RouteStatus.PUBLISHED || route.getStatus() == RouteStatus.FULL
                ? matchingProperties.bookableUntil(route.getDepartureTime()) : null;
    }

    /** Why this rider can't book this route right now, in words the app shows as-is - null when they can.
     * Mirrors reserveSeats()'s checks; the overlap rule is left to the reserve call itself. */
    private String unavailableReason(RoutePost route, String callerId) {
        if (callerId != null && callerId.equals(route.getDriverId())) {
            return "This is your own route";
        }
        return switch (route.getStatus()) {
            case CANCELLED -> "This ride has been cancelled";
            case IN_PROGRESS, COMPLETED -> "This ride has already left";
            case FULL -> "This ride is full";
            default -> {
                if (!Instant.now().isBefore(matchingProperties.bookableUntil(route.getDepartureTime()))) {
                    yield "This ride is no longer taking bookings";
                }
                if (route.getSeatsAvailable() == null || route.getSeatsAvailable() <= 0) {
                    yield "This ride is full";
                }
                yield callerId == null ? null : riderReason(route, callerId);
            }
        };
    }

    /** The rider's own reasons, same wording as reserveSeats() - so the button says it before they tap. */
    private String riderReason(RoutePost route, String riderId) {
        if (suspendedUserRepository.existsById(riderId)) {
            return "This account is suspended. Contact CoRyd support.";
        }
        if (userBlockService.keptApart(riderId, route.getDriverId())) {
            return "This ride isn't available to you";
        }
        if (identityVerificationPolicy.blocksUnverified() && !identityVerificationCacheRepository.existsByUserIdAndVerifiedTrue(riderId)) {
            return "Verify your identity to book a ride";
        }
        return null;
    }

    /** Best-effort, same "a Maps hiccup shouldn't take down the screen" pattern as pickupEtaMinutesBatch() - a Directions failure means no line on the map, not a broken screen. Cached 7 days in MapsService keyed by rounded coordinates, so this is a fresh Directions call only the first time any two points are looked up. Boxed params (not primitives) - unboxing a null before this method's own try/catch could see it would throw uncaught, not degrade gracefully. */
    private String overviewPolyline(Double originLat, Double originLng, Double destLat, Double destLng) {
        if (originLat == null || originLng == null || destLat == null || destLng == null) {
            return null;
        }
        try {
            return mapsService.getRoutePreview(originLat, originLng, destLat, destLng).overviewPolyline();
        } catch (Exception e) {
            log.warn("Route preview unavailable for {},{} -> {},{}: {}", originLat, originLng, destLat, destLng, e.getMessage());
            return null;
        }
    }

    /** Road-following route through the whole current manifest - origin -> every still-active
     * pickup -> destination - for the driver's Ready to Depart / Live Trip map and the rider's
     * Live Trip map. Falls back to a direct origin->destination preview (no waypoints) when
     * there are no confirmed pickups yet, or when there are more than a single Directions call
     * can take - a degraded straight-line-for-the-uncovered-stops map beats no map at all. */
    public MultiStopRoutePreview getMultiStopRoutePreview(String routePostId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        return getMultiStopRoutePreview(routePostId, route.getOriginLat(), route.getOriginLng());
    }

    /** Same as the 1-arg overload, but starting from originLat/originLng instead of the route's
     * originally-posted origin - RouteDeviationService uses this to reroute from the driver's
     * live position once they've meaningfully left the plotted line. */
    public MultiStopRoutePreview getMultiStopRoutePreview(String routePostId, double originLat, double originLng) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        List<Booking> stops = bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> MANIFEST_STOP_STATUSES.contains(b.getStatus()))
                .filter(b -> b.getPickupLat() != null && b.getPickupLng() != null)
                .toList();

        if (stops.isEmpty() || stops.size() > MAX_MULTI_STOP_WAYPOINTS) {
            if (stops.size() > MAX_MULTI_STOP_WAYPOINTS) {
                log.warn("Route {} has {} active pickups, over the {}-waypoint cap - falling back to a direct origin->destination preview",
                        routePostId, stops.size(), MAX_MULTI_STOP_WAYPOINTS);
            }
            RoutePreview direct = mapsService.getRoutePreview(originLat, originLng,
                    route.getDestinationLat(), route.getDestinationLng());
            // The road the driver picked, while they're still starting from the route's own start.
            boolean pickedRoad = route.getViaSummary() != null && route.getRoutePolyline() != null
                    && originLat == route.getOriginLat() && originLng == route.getOriginLng();
            return new MultiStopRoutePreview(
                    pickedRoad ? Math.round(PolylineMath.lengthMeters(PolylineDecoder.decode(route.getRoutePolyline())) / 10.0) / 100.0 : direct.distanceKm(),
                    pickedRoad && route.getEstimatedDurationMinutes() != null ? route.getEstimatedDurationMinutes() : direct.durationMinutes(),
                    pickedRoad ? route.getRoutePolyline() : direct.overviewPolyline(), List.of());
        }

        List<double[]> waypointCoords = stops.stream().map(b -> new double[]{b.getPickupLat(), b.getPickupLng()}).toList();
        DirectionsMultiStopResult result = mapsService.getMultiStopRoutePreview(
                originLat, originLng, route.getDestinationLat(), route.getDestinationLng(), waypointCoords);

        List<String> orderedBookingIds = result.waypointOrder().isEmpty()
                ? stops.stream().map(BaseEntity::getId).toList()
                : result.waypointOrder().stream().map(i -> stops.get(i).getId()).toList();

        return new MultiStopRoutePreview(result.distanceKm(), result.durationMinutes(), result.overviewPolyline(), orderedBookingIds);
    }

    /** Batch-joins driver/vehicle/rating data onto raw route matches, plus one batched Google Distance Matrix call for pickup-ETA - four calls total for the whole page, never one per row. */
    private List<RideSearchResult> enrich(List<RoutePost> routes, double riderLat, double riderLng) {
        return enrich(routes, riderLat, riderLng, Map.of(), Set.of(), null, null);
    }

    /** along: where the rider meets each route on its road (their card carries the pickup and dropoff, and the pickup
     * ETA is to that point). partWay: the ones the rider joins part-way, which wouldn't have matched end to end.
     * pickupLabel/dropoffLabel: the place names the rider searched with, carried on those stops. */
    private List<RideSearchResult> enrich(List<RoutePost> routes, double riderLat, double riderLng,
                                          Map<String, RouteMatcher.Match> along, Set<String> partWay,
                                          String pickupLabel, String dropoffLabel) {
        List<String> driverIds = routes.stream().map(RoutePost::getDriverId).distinct().toList();
        List<String> vehicleIds = routes.stream().map(RoutePost::getVehicleId).filter(Objects::nonNull).distinct().toList();

        Map<String, ProfileServiceClient.DriverSummaryItem> drivers = profileServiceClient.fetchDriverSummaries(driverIds);
        Map<String, ProfileServiceClient.VehicleSummaryItem> vehicles = profileServiceClient.fetchVehicleSummaries(vehicleIds);
        Map<String, RatingRepository.RatingSummary> ratings = ratingRepository.summarize(driverIds).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, r -> r));
        List<Integer> pickupEtaMinutes = pickupEtaMinutesBatch(routes, riderLat, riderLng, along);

        return IntStream.range(0, routes.size()).mapToObj(i -> {
            RoutePost route = routes.get(i);
            ProfileServiceClient.DriverSummaryItem driver = drivers.get(route.getDriverId());
            ProfileServiceClient.VehicleSummaryItem vehicle = route.getVehicleId() == null ? null : vehicles.get(route.getVehicleId());
            RatingRepository.RatingSummary rating = ratings.get(route.getDriverId());

            return new RideSearchResult(
                    route.getId(),
                    route.getOriginLabel(),
                    route.getDestinationLabel(),
                    route.getDepartureTime(),
                    route.getFarePerSeat(),
                    route.getSeatsAvailable(),
                    route.getDriverId(),
                    driver == null ? null : driver.legalName(),
                    driver == null ? null : driver.photoUrl(),
                    isFullyVerified(route.getDriverId()),
                    vehicle == null ? null : vehicle.make(),
                    vehicle == null ? null : vehicle.model(),
                    vehicle == null ? null : vehicle.plateNumber(),
                    vehicle != null && vehicle.hasAC(),
                    rating == null ? null : rating.getAvgStars(),
                    rating == null ? 0L : rating.getRatingCount(),
                    pickupEtaMinutes.get(i),
                    route.getRouteNote(),
                    partWay.contains(route.getId()),
                    stop(along.get(route.getId()), true, pickupLabel),
                    stop(along.get(route.getId()), false, dropoffLabel),
                    route.getViaSummary(),
                    vehicle == null ? null : vehicle.color(),
                    route.getTimeChangeCount(),
                    bookableUntil(route)
            );
        }).toList();
    }

    /** Best-effort, same "a Maps hiccup shouldn't take down the screen" pattern as BookingService.routeViaSummary() - on failure every row in the batch gets null instead of the whole search failing. */
    private static RideStop stop(RouteMatcher.Match match, boolean pickup, String label) {
        if (match == null) {
            return null;
        }
        RouteMatcher.Point p = pickup ? match.pickup() : match.dropoff();
        return new RideStop(p.lat(), p.lng(), p.walkMeters(), p.time(), label);
    }

    private List<Integer> pickupEtaMinutesBatch(List<RoutePost> routes, double riderLat, double riderLng, Map<String, RouteMatcher.Match> along) {
        if (routes.isEmpty()) {
            return List.of();
        }
        try {
            List<double[]> origins = routes.stream()
                    .map(r -> along.containsKey(r.getId())
                            ? new double[]{along.get(r.getId()).pickup().lat(), along.get(r.getId()).pickup().lng()}
                            : new double[]{r.getOriginLat(), r.getOriginLng()})
                    .toList();
            List<DistanceMatrixResult> results = mapsService.getDistanceMatrixBatch(riderLat, riderLng, origins);
            // enrich() zips this by index - a short or missing answer must blank the ETAs, not crash the search.
            if (results == null || results.size() != routes.size()) {
                throw new IllegalStateException("expected " + routes.size() + " ETAs, got " + (results == null ? 0 : results.size()));
            }
            return results.stream().map(DistanceMatrixResult::durationMinutes).toList();
        } catch (Exception e) {
            log.warn("Pickup-ETA batch unavailable for {} results: {}", routes.size(), e.getMessage());
            return routes.stream().map(r -> (Integer) null).toList();
        }
    }

    /** "My Routes" / dashboard repost card - newest first, no ownership check since this is a read (mutations are what's gated). */
    public PagedResponse<RoutePost> findByDriver(String driverId, Pageable pageable) {
        return PagedResponse.from(routePostRepository.findByDriverIdOrderByCreatedAtDesc(driverId, pageable));
    }

    /** Batch lookup for a client resolving routePostIds from elsewhere (e.g. the driver earnings screen's trip list) into display text - one call for a whole page, not one per row. */
    public List<RouteSummary> getSummaries(List<String> routePostIds) {
        if (routePostIds == null || routePostIds.isEmpty()) {
            return List.of();
        }
        Map<String, List<Booking>> bookingsByRoute = bookingRepository.findByRoutePostIdIn(routePostIds).stream()
                .filter(b -> b.getStatus() != BookingStatus.HELD)
                .collect(Collectors.groupingBy(Booking::getRoutePostId));

        return routePostRepository.findAllById(routePostIds).stream()
                .map(route -> new RouteSummary(
                        route.getId(), route.getOriginLabel(), route.getDestinationLabel(), route.getDepartureTime(),
                        bookingsByRoute.getOrDefault(route.getId(), List.of()).stream().mapToInt(Booking::getSeatCount).sum()
                ))
                .toList();
    }

    /** "Rides nearby" map - each pin is a bookable route the app hands to getRouteDetails(). lat/lng is
     * the route's origin, which is a public meeting point by product decision. No pickup ETA here -
     * that's a Maps call per pin; the details screen computes it for the one route tapped. */
    public NearbyActivityResponse getNearbyActivity(double lat, double lng, double radiusKm, String callerId) {
        Instant now = Instant.now();
        Instant closedBefore = now.minus(matchingProperties.getBookingGraceMinutes(), ChronoUnit.MINUTES);
        List<RoutePost> nearby = withoutBlocked(routePostRepository.findNearbyOrigins(lat, lng, radiusKm, closedBefore,
                now.plus(NEARBY_OPTIONS_TIME_WINDOW_HOURS, ChronoUnit.HOURS), callerId), callerId);
        if (nearby.isEmpty()) {
            return new NearbyActivityResponse(0, List.of());
        }

        List<String> driverIds = nearby.stream().map(RoutePost::getDriverId).distinct().toList();
        List<String> vehicleIds = nearby.stream().map(RoutePost::getVehicleId).filter(Objects::nonNull).distinct().toList();
        Map<String, ProfileServiceClient.DriverSummaryItem> drivers = profileServiceClient.fetchDriverSummaries(driverIds);
        Map<String, ProfileServiceClient.VehicleSummaryItem> vehicles = profileServiceClient.fetchVehicleSummaries(vehicleIds);
        Map<String, RatingRepository.RatingSummary> ratings = ratingRepository.summarize(driverIds).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, r -> r));

        List<NearbyRidePin> pins = nearby.stream().map(r -> {
            ProfileServiceClient.DriverSummaryItem driver = drivers.get(r.getDriverId());
            ProfileServiceClient.VehicleSummaryItem vehicle = r.getVehicleId() == null ? null : vehicles.get(r.getVehicleId());
            RatingRepository.RatingSummary rating = ratings.get(r.getDriverId());
            return new NearbyRidePin(r.getOriginLat(), r.getOriginLng(), r.getId(),
                    r.getOriginLabel(), r.getDestinationLabel(), r.getDepartureTime(), r.getSeatsAvailable(), r.getFarePerSeat(),
                    driver == null ? null : driver.firstName(),
                    driver == null ? null : driver.photoUrl(),
                    rating == null ? null : rating.getAvgStars(),
                    vehicle == null ? null : vehicle.make(),
                    vehicle == null ? null : vehicle.model());
        }).toList();
        return new NearbyActivityResponse(pins.size(), pins);
    }

    public Optional<SuggestedFareRange> suggestedFare(String originLabel, String destinationLabel) {
        return suggestedFareRangeRepository.findByCorridorKey(corridorKey(originLabel, destinationLabel));
    }

    /** "Start Trip" button on the Ready to Depart screen - moves the route out of the booking window and into its in-progress phase, where manifest marking and Complete Trip become available. */
    @Transactional
    public void startTrip(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can start it");
        }
        if (route.getStatus() != RouteStatus.PUBLISHED && route.getStatus() != RouteStatus.FULL) {
            throw new BadRequestException("This trip can't be started from its current state");
        }
        // Start opens with the active window - earlier would pull riders into trip mode, and the route
        // out of search, hours ahead of the time they booked.
        Instant opensAt = route.getDepartureTime().minus(TripScheduleService.ACTIVE_WINDOW);
        if (Instant.now().isBefore(opensAt)) {
            throw new BadRequestException("You can start this trip from " + TripScheduleService.formatLagos(opensAt)
                    + ", " + TripScheduleService.ACTIVE_WINDOW.toMinutes() + " minutes before departure");
        }
        route.setStatus(RouteStatus.IN_PROGRESS);
        route.setStartedAt(Instant.now());
        routePostRepository.save(route);
        eventPublisher.publishEvent(new RouteSearchRelevantChangeEvent(routePostId));
    }

    /**
     * D-104's gate, relaxed while there is no ops dashboard to approve anyone. Identity and vehicle
     * details must be on file before the first route - they identify who is carrying passengers, and
     * needing only submission rather than approval keeps them enforceable with no ops in the loop.
     * Insurance and roadworthiness are what the grace window actually defers.
     */
    private void requirePostingEligibility(String driverId) {
        DriverVerificationCache cache = driverVerificationCacheRepository.findById(driverId)
                .orElseGet(() -> new DriverVerificationCache(driverId, false));
        if (cache.isApproved() || verificationPolicy.neverEnforces()) {
            return;
        }
        if (verificationPolicy.blocksUnverified()) {
            throw new BadRequestException("Driver/vehicle is not yet verified - cannot post a route (D-104)");
        }

        requirePrerequisites(cache);

        Instant now = Instant.now();
        if (cache.getFirstRouteAt() == null) {
            cache.setFirstRouteAt(now);
            driverVerificationCacheRepository.save(cache);
            return;
        }
        if (verificationPolicy.graceExpired(cache.getFirstRouteAt(), now)) {
            throw new BadRequestException(
                    "Your document upload window has closed - upload your insurance and roadworthiness certificate to post routes again");
        }
    }

    /** Earnings are split to the driver's bank account when a rider pays, so it must exist before anyone can book,
     * and a driver owing too much from refunded trips pays it back first. Applies to approved drivers too. An
     * unreachable payment-service doesn't block, same as the eligibility check. */
    private void requirePaymentStanding(String driverId) {
        PaymentServiceClient.PostingStatus status = paymentServiceClient.postingStatus(driverId);
        if (status == null) {
            return;
        }
        if (!status.hasPayoutAccount()) {
            throw new BadRequestException("Add the bank account your earnings will be paid to before posting a route");
        }
        if (status.overOwedLimit()) {
            throw new BadRequestException("You owe ₦" + status.amountOwed().toPlainString()
                    + " from refunded trips - pay it back in the app to post routes again");
        }
    }

    /**
     * Only cached at SUBMITTED level - an upload cannot be undone, but an approval can be revoked,
     * so at APPROVED level profile-service is asked on every post.
     */
    private void requirePrerequisites(DriverVerificationCache cache) {
        boolean requireApproved = verificationPolicy.requiresApprovedPrerequisites();
        if (!requireApproved && cache.getPrerequisitesMetAt() != null) {
            return;
        }

        ProfileServiceClient.DriverOnboarding onboarding = profileServiceClient.fetchOnboarding(cache.getDriverId());
        if (!onboarding.identityMeets(requireApproved)) {
            throw new BadRequestException(requireApproved
                    ? "Your government ID is still being reviewed - you can post routes once it is approved"
                    : "Upload your government ID before posting a route");
        }
        if (!onboarding.vehicleMeets(requireApproved)) {
            throw new BadRequestException(requireApproved
                    ? "Your vehicle is still being reviewed - you can post routes once it is approved"
                    : "Register your vehicle - make, model, colour and plate number - before posting a route");
        }

        if (cache.getPrerequisitesMetAt() == null) {
            cache.setPrerequisitesMetAt(Instant.now());
            driverVerificationCacheRepository.save(cache);
        }
    }

    /**
     * Whether the driver is verified as a person - the same answer for posting a route and for posting-eligibility.
     * The local copy of an approved identity check settles it; otherwise profile-service's rule decides, which also
     * accepts a lookup driver whose NIN is confirmed and licence approved. Unreachable profile-service: not verified.
     */
    private boolean driverIdentityVerified(String driverId) {
        if (identityVerificationCacheRepository.existsByUserIdAndVerifiedTrue(driverId)) {
            return true;
        }
        try {
            return profileServiceClient.fetchOnboarding(driverId).identityVerified();
        } catch (Exception e) {
            log.warn("Could not read identity verification for driver {}: {}", driverId, e.getMessage());
            return false;
        }
    }

    /** Drives the driver app's onboarding gate and its document countdown. */
    @Transactional(readOnly = true)
    public DriverPostingEligibilityResponse postingEligibility(String driverId) {
        DriverVerificationCache cache = driverVerificationCacheRepository.findById(driverId).orElse(null);
        boolean approved = cache != null && cache.isApproved();
        Instant firstRouteAt = cache == null ? null : cache.getFirstRouteAt();
        Instant deadline = verificationPolicy.deadlineFrom(firstRouteAt);

        boolean requireApproved = verificationPolicy.requiresApprovedPrerequisites();
        boolean identityOk = approved;
        boolean vehicleOk = approved;
        if (!approved && !requireApproved && cache != null && cache.getPrerequisitesMetAt() != null) {
            identityOk = true;
            vehicleOk = true;
        } else if (!approved) {
            try {
                ProfileServiceClient.DriverOnboarding onboarding = profileServiceClient.fetchOnboarding(driverId);
                identityOk = onboarding.identityMeets(requireApproved);
                vehicleOk = onboarding.vehicleMeets(requireApproved);
            } catch (Exception e) {
                log.warn("Could not read prerequisites for driver {}: {}", driverId, e.getMessage());
            }
        }

        boolean identityVerified = driverIdentityVerified(driverId);
        boolean graceOver = verificationPolicy.graceExpired(firstRouteAt, Instant.now());
        String blockedReason = null;
        if (identityVerificationPolicy.blocksUnverified() && !identityVerified) {
            blockedReason = "IDENTITY_NOT_VERIFIED";
        } else if (!approved && !verificationPolicy.neverEnforces()) {
            if (verificationPolicy.blocksUnverified()) {
                blockedReason = "AWAITING_APPROVAL";
            } else if (!identityOk) {
                blockedReason = requireApproved ? "IDENTITY_PENDING_APPROVAL" : "IDENTITY_REQUIRED";
            } else if (!vehicleOk) {
                blockedReason = requireApproved ? "VEHICLE_PENDING_APPROVAL" : "VEHICLE_REQUIRED";
            }
        }
        PaymentServiceClient.PostingStatus payment = paymentServiceClient.postingStatus(driverId);
        boolean payoutAccountOk = payment == null || payment.hasPayoutAccount();
        if (blockedReason == null && !payoutAccountOk) {
            blockedReason = "PAYOUT_ACCOUNT_REQUIRED";
        }
        if (blockedReason == null && payment != null && payment.overOwedLimit()) {
            blockedReason = "AMOUNT_OWED";
        }
        if (blockedReason == null && !approved && !verificationPolicy.neverEnforces() && graceOver) {
            blockedReason = "DOCUMENTS_OVERDUE";
        }

        Integer daysRemaining = deadline == null || approved ? null
                : (int) Math.max(0, Duration.between(Instant.now(), deadline).toDays());

        return new DriverPostingEligibilityResponse(
                blockedReason == null,
                approved,
                identityOk,
                identityVerified,
                vehicleOk,
                payoutAccountOk,
                payment == null ? null : payment.amountOwed(),
                blockedReason,
                verificationPolicy.getPrerequisiteLevel().name(),
                verificationPolicy.getEnforcement().name(),
                verificationPolicy.getGracePeriodDays(),
                firstRouteAt,
                approved ? null : deadline,
                daysRemaining);
    }

    /**
     * Rider-facing "verified driver" badge. Reads the six-item approval gate, not
     * DriverSummary.verificationStatus, which is identity-only and would
     * show a green tick on a driver operating under the document grace window.
     */
    private boolean isFullyVerified(String driverId) {
        return driverVerificationCacheRepository.findById(driverId)
                .map(DriverVerificationCache::isApproved)
                .orElse(false);
    }

    /** Anything not already a terminal state - HELD/CONFIRMED plus the trip-in-progress phases, all of which a driver-side route cancellation must still unwind and refund. */
    private static final List<BookingStatus> CANCELLABLE_BY_ROUTE_CANCELLATION = List.of(
            BookingStatus.RESERVED, BookingStatus.HELD, BookingStatus.CONFIRMED,
            BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);

    /** Annotated because this is an entry point: the delegation below is a self-call, so the
     * proxy is never consulted again and the inner @Transactional would not apply. */
    @Transactional
    public void cancel(String routePostId, String callerId) {
        cancel(routePostId, callerId, "DRIVER");
    }

    /** cancelledBy also drives the push copy - RouteExpiryJob passes CANCELLED_NOT_STARTED so riders are
     * told the driver never started the trip, rather than that the driver actively cancelled it. */
    @Transactional
    public void cancel(String routePostId, String callerId, String cancelledBy) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can cancel it");
        }
        if (route.getStatus() == RouteStatus.CANCELLED) {
            return;
        }
        if (route.getStatus() == RouteStatus.COMPLETED) {
            throw new BadRequestException("This trip is already completed - it can't be cancelled");
        }
        cancelRoute(route, CancellationLogService.Context.of(cancelledBy, callerId));
    }

    /** cancelledBy on every event of a whole trip ops cancelled - riders get support's copy, the driver isn't told once per seat. */
    public static final String CANCELLED_BY_OPS_TRIP = "OPS_TRIP";

    /**
     * Ops cancels a trip on the driver's behalf - the same refund-and-notify cascade as the driver's own cancel, every
     * paid rider refunded in full whatever the fault. A trip already finished is refused rather than ignored, so ops
     * never believes it cancelled something that had already ended. Returns the paid bookings that were refunded.
     */
    @Transactional
    public List<String> cancelAsOps(String routePostId, String actorId, String actorName, String reason,
                                    org.coryd.trip.entity.CancellationFault fault) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));
        if (route.getStatus() == RouteStatus.CANCELLED) {
            throw new BadRequestException("This trip is already cancelled");
        }
        if (route.getStatus() == RouteStatus.COMPLETED) {
            throw new BadRequestException("This trip is already completed - it can't be cancelled");
        }
        List<String> refunded = cancelRoute(route,
                new CancellationLogService.Context(CANCELLED_BY_OPS_TRIP, actorId, actorName, reason, fault));
        if (refunded == null) {
            throw new BadRequestException("This trip changed while you were cancelling it - reload and try again");
        }
        return refunded;
    }

    private static final List<RouteStatus> CANCELLABLE_ROUTE_STATUSES = List.of(
            RouteStatus.DRAFT, RouteStatus.PUBLISHED, RouteStatus.FULL, RouteStatus.IN_PROGRESS);

    /** Null when another writer finished or cancelled the route first - nothing was done. */
    private List<String> cancelRoute(RoutePost route, CancellationLogService.Context context) {
        String routePostId = route.getId();
        RouteStatus before = route.getStatus();
        // Compare-and-set, so two cancellations landing together can't both send the route's refunds and pushes.
        if (routePostRepository.transitionStatus(routePostId, CANCELLABLE_ROUTE_STATUSES, RouteStatus.CANCELLED) == 0) {
            return null;
        }
        route.setStatus(RouteStatus.CANCELLED);
        driverStatsPublisher.changed(route.getDriverId());
        org.coryd.trip.entity.CancellationLog routeRow = cancellationLogService.recordRoute(route, before, context);

        String cancelledBy = context.cancelledBy();
        List<String> refundedIds = new ArrayList<>();
        for (Booking booking : bookingRepository.findByRoutePostId(routePostId)) {
            BookingStatus bookingBefore = booking.getStatus();
            // Compare-and-set so a booking the rider cancelled a moment earlier isn't cancelled (and refunded) twice.
            if (bookingRepository.transitionStatus(booking.getId(), CANCELLABLE_BY_ROUTE_CANCELLATION, BookingStatus.CANCELLED_DRIVER) == 1) {
                booking.setStatus(BookingStatus.CANCELLED_DRIVER);
                boolean paid = booking.getConfirmedAt() != null;
                if (paid) {
                    refundedIds.add(booking.getId());
                    org.coryd.trip.entity.CancellationLog seat = cancellationLogService.recordBooking(
                            booking, route, before, bookingBefore, context, null, routeRow.getId());
                    if (seat != null) {
                        cancellationLogService.addToRoute(routeRow, seat);
                    }
                }
                kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                        .bookingId(booking.getId())
                        .routePostId(routePostId)
                        .riderId(booking.getRiderId())
                        .driverId(route.getDriverId())
                        .cancelledBy(cancelledBy)
                        .feeApplied(false)
                        .paid(paid)
                        .cancelledAt(LocalDateTime.now())
                        .build());
            }
        }

        kafkaTemplate.send(KafkaTopics.ROUTE_CANCELLED, RouteCancelledEvent.builder()
                .routePostId(routePostId)
                .driverId(route.getDriverId())
                .affectedBookingIds(refundedIds)
                .cancelledBy(cancelledBy)
                .cancelledAt(LocalDateTime.now())
                .build());

        // Same as completion - the route's group chat has no reason to outlive the route itself.
        chatService.deleteChatForRoute(routePostId);
        eventPublisher.publishEvent(new RouteSearchRelevantChangeEvent(routePostId));
        return refundedIds;
    }

    /**
     * Buckets postings by origin/destination pair. Labels come straight from Places
     * autocomplete, so they carry incidental case and whitespace variation - normalising
     * here keeps the driver's posted key and the rider's suggested-fare lookup landing in
     * the same bucket. Both labels are capped at 255 chars by PostRouteRequest, so the key
     * always fits corridor_key VARCHAR(512).
     */
    private String corridorKey(String originLabel, String destinationLabel) {
        return normalizeLabel(originLabel) + "->" + normalizeLabel(destinationLabel);
    }

    private static String normalizeLabel(String label) {
        if (label == null) {
            return "";
        }
        return label.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

}
