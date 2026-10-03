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
import org.coryd.common.kafka.events.NotifyMeMatchedEvent;
import org.coryd.common.kafka.events.RouteCancelledEvent;
import org.coryd.common.kafka.events.RoutePublishedEvent;
import org.coryd.common.kafka.events.SeatReservedEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.policy.DriverVerificationPolicy;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.dto.DriverPostingEligibilityResponse;
import org.coryd.trip.dto.DirectionsMultiStopResult;
import org.coryd.trip.dto.DistanceMatrixResult;
import org.coryd.trip.dto.MultiStopRoutePreview;
import org.coryd.trip.dto.NearbyActivityResponse;
import org.coryd.trip.dto.NearbyRidePin;
import org.coryd.trip.dto.PostRouteRequest;
import org.coryd.trip.dto.RideDetailsResponse;
import org.coryd.trip.dto.RideSearchResult;
import org.coryd.trip.dto.RoutePreview;
import org.coryd.trip.event.RouteSearchRelevantChangeEvent;
import org.coryd.trip.dto.RouteSummary;
import org.coryd.trip.dto.SearchRequest;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.DriverVerificationCache;
import org.coryd.trip.entity.FareReport;
import org.coryd.trip.entity.NotifyMeRequest;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SuggestedFareRange;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.DriverVerificationCacheRepository;
import org.coryd.trip.repository.FareReportRepository;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SuggestedFareRangeRepository;
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
    private final NotifyMeRequestRepository notifyMeRequestRepository;
    private final BookingRepository bookingRepository;
    private final DriverVerificationCacheRepository driverVerificationCacheRepository;
    private final RatingRepository ratingRepository;
    private final ProfileServiceClient profileServiceClient;
    private final MapsService mapsService;
    private final MatchingProperties matchingProperties;
    private final DriverVerificationPolicy verificationPolicy;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ChatService chatService;
    private final LiveSearchSessionRegistry liveSearchSessionRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final TripScheduleService tripScheduleService;

    /** cancelledBy for a route auto-cancelled because its driver never started it. */
    public static final String CANCELLED_NOT_STARTED = "NOT_STARTED";

    /** A "leave now" post can land a few seconds after the departure the app filled in. */
    private static final Duration DEPARTURE_PAST_TOLERANCE = Duration.ofMinutes(5);

    @Transactional
    public RoutePost post(String driverId, String callerId, PostRouteRequest request) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can post their own route");
        }
        if (!request.departureTime().isAfter(Instant.now().minus(DEPARTURE_PAST_TOLERANCE))) {
            throw new BadRequestException("Departure time has already passed");
        }
        requirePostingEligibility(driverId);
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

        RoutePost route = RoutePost.builder()
                .estimatedDurationMinutes(estimateDurationMinutes(request))
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

    /** Driving time for the overlap window - a Maps failure falls back to the default rather than
     * blocking the post. Same cached preview the route screen already uses. */
    private Integer estimateDurationMinutes(PostRouteRequest request) {
        try {
            RoutePreview preview = mapsService.getRoutePreview(request.originLat(), request.originLng(),
                    request.destinationLat(), request.destinationLng());
            return preview == null || preview.durationMinutes() <= 0 ? null : preview.durationMinutes();
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
        routePostRepository.findById(routePostId).ifPresent(this::notifyMatchingWaitlist);
    }

    private void notifyMatchingWaitlist(RoutePost route) {
        double radiusKm = matchingProperties.getGeoToleranceMeters() / 1000.0;
        Instant earliest = route.getDepartureTime().minus(matchingProperties.getTimeToleranceMinutes(), ChronoUnit.MINUTES);
        Instant latest = route.getDepartureTime().plus(matchingProperties.getTimeToleranceMinutes(), ChronoUnit.MINUTES);

        List<NotifyMeRequest> matches = notifyMeRequestRepository.findMatching(
                Instant.now(), earliest, latest,
                route.getOriginLat(), route.getOriginLng(),
                route.getDestinationLat(), route.getDestinationLng(),
                radiusKm
        );

        for (NotifyMeRequest request : matches) {
            request.setFulfilled(true);
            notifyMeRequestRepository.save(request);
            kafkaTemplate.send(KafkaTopics.NOTIFY_ME_MATCHED, NotifyMeMatchedEvent.builder()
                    .notifyMeRequestId(request.getId())
                    .riderId(request.getRiderId())
                    .routePostId(route.getId())
                    .build());
        }
    }

    public List<RideSearchResult> search(SearchRequest request) {
        double radiusKm = matchingProperties.getGeoToleranceMeters() / 1000.0;
        Instant earliest = request.desiredDepartureTime().minus(matchingProperties.getTimeToleranceMinutes(), ChronoUnit.MINUTES);
        Instant latest = request.desiredDepartureTime().plus(matchingProperties.getTimeToleranceMinutes(), ChronoUnit.MINUTES);

        List<RoutePost> matches = routePostRepository.search(
                earliest, latest,
                request.originLat(), request.originLng(),
                request.destinationLat(), request.destinationLng(),
                radiusKm
        );

        return enrich(matches, request.originLat(), request.originLng());
    }

    /** Same search, but also registers/refreshes a live-search session so the rider can subscribe
     * to /topic/search-sessions/{sessionId} and get pushed updates instead of polling this endpoint -
     * see LiveSearchPushService/LiveSearchSessionRegistry. sessionId is client-generated (a UUID the
     * app keeps for the life of the search screen); riderId is null-safe only in that a null
     * sessionId skips registration entirely - a real search always has an authenticated caller. */
    public List<RideSearchResult> search(SearchRequest request, String sessionId, String riderId) {
        List<RideSearchResult> results = search(request);
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
    public List<RideSearchResult> getNearbyOptions(SearchRequest request) {
        double radiusKm = (matchingProperties.getGeoToleranceMeters() / 1000.0) * NEARBY_OPTIONS_RADIUS_MULTIPLIER;
        Instant earliest = request.desiredDepartureTime();
        Instant latest = request.desiredDepartureTime().plus(NEARBY_OPTIONS_TIME_WINDOW_HOURS, ChronoUnit.HOURS);

        List<RoutePost> matches = routePostRepository.search(
                earliest, latest,
                request.originLat(), request.originLng(),
                request.destinationLat(), request.destinationLng(),
                radiusKm
        );

        List<RoutePost> capped = matches.stream().limit(NEARBY_OPTIONS_LIMIT).toList();
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
                overviewPolyline(route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng()),
                unavailableReason == null, unavailableReason
        );
    }

    /** Why this rider can't book this route right now, in words the app shows as-is - null when they can.
     * Mirrors reserveSeats()'s checks; the overlap rule is left to the reserve call itself. */
    private static String unavailableReason(RoutePost route, String callerId) {
        if (callerId != null && callerId.equals(route.getDriverId())) {
            return "This is your own route";
        }
        return switch (route.getStatus()) {
            case CANCELLED -> "This ride has been cancelled";
            case IN_PROGRESS, COMPLETED -> "This ride has already left";
            case FULL -> "This ride is full";
            default -> {
                if (!route.getDepartureTime().isAfter(Instant.now())) {
                    yield "This ride has already departed";
                }
                yield route.getSeatsAvailable() == null || route.getSeatsAvailable() <= 0 ? "This ride is full" : null;
            }
        };
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
            return new MultiStopRoutePreview(direct.distanceKm(), direct.durationMinutes(), direct.overviewPolyline(), List.of());
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
        List<String> driverIds = routes.stream().map(RoutePost::getDriverId).distinct().toList();
        List<String> vehicleIds = routes.stream().map(RoutePost::getVehicleId).filter(Objects::nonNull).distinct().toList();

        Map<String, ProfileServiceClient.DriverSummaryItem> drivers = profileServiceClient.fetchDriverSummaries(driverIds);
        Map<String, ProfileServiceClient.VehicleSummaryItem> vehicles = profileServiceClient.fetchVehicleSummaries(vehicleIds);
        Map<String, RatingRepository.RatingSummary> ratings = ratingRepository.summarize(driverIds).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, r -> r));
        List<Integer> pickupEtaMinutes = pickupEtaMinutesBatch(routes, riderLat, riderLng);

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
                    route.getRouteNote()
            );
        }).toList();
    }

    /** Best-effort, same "a Maps hiccup shouldn't take down the screen" pattern as BookingService.routeViaSummary() - on failure every row in the batch gets null instead of the whole search failing. */
    private List<Integer> pickupEtaMinutesBatch(List<RoutePost> routes, double riderLat, double riderLng) {
        if (routes.isEmpty()) {
            return List.of();
        }
        try {
            List<double[]> origins = routes.stream()
                    .map(r -> new double[]{r.getOriginLat(), r.getOriginLng()})
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

    /** Batch lookup for a client resolving routePostIds from elsewhere (e.g. the driver wallet's transaction feed) into display text - one call for a whole page, not one per row. */
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
        List<RoutePost> nearby = routePostRepository.findNearbyOrigins(lat, lng, radiusKm, now,
                now.plus(NEARBY_OPTIONS_TIME_WINDOW_HOURS, ChronoUnit.HOURS), callerId);
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

    @Transactional
    public NotifyMeRequest requestNotification(String riderId, SearchRequest request) {
        return notifyMeRequestRepository.save(NotifyMeRequest.builder()
                .riderId(riderId)
                .originLat(request.originLat())
                .originLng(request.originLng())
                .destinationLat(request.destinationLat())
                .destinationLng(request.destinationLng())
                .desiredDepartureTime(request.desiredDepartureTime())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build());
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

        boolean graceOver = verificationPolicy.graceExpired(firstRouteAt, Instant.now());
        String blockedReason = null;
        if (!approved && !verificationPolicy.neverEnforces()) {
            if (verificationPolicy.blocksUnverified()) {
                blockedReason = "AWAITING_APPROVAL";
            } else if (!identityOk) {
                blockedReason = requireApproved ? "IDENTITY_PENDING_APPROVAL" : "IDENTITY_REQUIRED";
            } else if (!vehicleOk) {
                blockedReason = requireApproved ? "VEHICLE_PENDING_APPROVAL" : "VEHICLE_REQUIRED";
            } else if (graceOver) {
                blockedReason = "DOCUMENTS_OVERDUE";
            }
        }

        Integer daysRemaining = deadline == null || approved ? null
                : (int) Math.max(0, Duration.between(Instant.now(), deadline).toDays());

        return new DriverPostingEligibilityResponse(
                blockedReason == null,
                approved,
                identityOk,
                vehicleOk,
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

        route.setStatus(RouteStatus.CANCELLED);
        routePostRepository.save(route);

        List<Booking> affected = bookingRepository.findByRoutePostId(routePostId);
        List<String> affectedIds = affected.stream().map(BaseEntity::getId).toList();

        for (Booking booking : affected) {
            // Compare-and-set so a booking the rider cancelled a moment earlier isn't cancelled (and refunded) twice.
            if (bookingRepository.transitionStatus(booking.getId(), CANCELLABLE_BY_ROUTE_CANCELLATION, BookingStatus.CANCELLED_DRIVER) == 1) {
                booking.setStatus(BookingStatus.CANCELLED_DRIVER);
                kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                        .bookingId(booking.getId())
                        .routePostId(routePostId)
                        .riderId(booking.getRiderId())
                        .driverId(route.getDriverId())
                        .cancelledBy(cancelledBy)
                        .feeApplied(false)
                        .cancelledAt(LocalDateTime.now())
                        .build());
            }
        }

        kafkaTemplate.send(KafkaTopics.ROUTE_CANCELLED, RouteCancelledEvent.builder()
                .routePostId(routePostId)
                .driverId(route.getDriverId())
                .affectedBookingIds(affectedIds)
                .cancelledBy(cancelledBy)
                .cancelledAt(LocalDateTime.now())
                .build());

        // Same as completion - the route's group chat has no reason to outlive the route itself.
        chatService.deleteChatForRoute(routePostId);
        eventPublisher.publishEvent(new RouteSearchRelevantChangeEvent(routePostId));
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
