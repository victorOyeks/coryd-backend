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
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.dto.DirectionsMultiStopResult;
import org.coryd.trip.dto.DistanceMatrixResult;
import org.coryd.trip.dto.GeoPin;
import org.coryd.trip.dto.MultiStopRoutePreview;
import org.coryd.trip.dto.NearbyActivityResponse;
import org.coryd.trip.dto.PostRouteRequest;
import org.coryd.trip.dto.RideDetailsResponse;
import org.coryd.trip.dto.RideSearchResult;
import org.coryd.trip.dto.RoutePreview;
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
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteService {

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
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public RoutePost post(String driverId, String callerId, PostRouteRequest request) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can post their own route");
        }
        boolean approved = driverVerificationCacheRepository.findById(driverId)
                .map(DriverVerificationCache::isApproved)
                .orElse(false);
        if (!approved) {
            throw new BadRequestException("Driver/vehicle is not yet verified - cannot post a route (D-104)");
        }

        boolean hasActiveTrip = !routePostRepository.findByDriverIdAndStatusInAndDepartureTimeAfterOrderByDepartureTimeAsc(
                driverId, List.of(RouteStatus.PUBLISHED, RouteStatus.FULL, RouteStatus.IN_PROGRESS), startOfToday()).isEmpty();
        if (hasActiveTrip) {
            throw new BadRequestException("You already have an active route - it must depart and complete, or be cancelled, before you can post another");
        }

        if (request.vehicleId() != null) {
            ProfileServiceClient.VehicleSummaryItem vehicle = profileServiceClient
                    .fetchVehicleSummaries(List.of(request.vehicleId()))
                    .get(request.vehicleId());
            if (vehicle != null && request.seatCapacity() > vehicle.seatCapacity()) {
                throw new BadRequestException("Seats offered can't exceed this vehicle's registered capacity (" + vehicle.seatCapacity() + ")");
            }
        }

        RoutePost route = RoutePost.builder()
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
        routePostRepository.save(route);

        fareReportRepository.save(FareReport.builder()
                .corridorKey(corridorKey(request.originLabel(), request.destinationLabel()))
                .farePerSeat(request.farePerSeat())
                .build());

        kafkaTemplate.send(KafkaTopics.ROUTE_PUBLISHED, RoutePublishedEvent.builder()
                .routePostId(route.getId())
                .driverId(driverId)
                .originLabel(route.getOriginLabel())
                .destinationLabel(route.getDestinationLabel())
                .departureTime(LocalDateTime.ofInstant(route.getDepartureTime(), java.time.ZoneOffset.UTC))
                .seatsAvailable(route.getSeatsAvailable())
                .build());

        notifyMatchingWaitlist(route);

        return route;
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
    public RideDetailsResponse getRouteDetails(String routePostId, double riderLat, double riderLng) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        RideSearchResult base = enrich(List.of(route), riderLat, riderLng).getFirst();

        return new RideDetailsResponse(
                base.routePostId(), base.originLabel(), base.destinationLabel(),
                route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng(),
                base.departureTime(),
                base.farePerSeat(), base.seatsAvailable(), base.driverId(), base.driverName(), base.driverPhotoUrl(),
                base.driverVerified(), base.vehicleMake(), base.vehicleModel(), base.vehiclePlate(), base.vehicleHasAC(),
                base.ratingAvg(), base.ratingCount(), base.pickupEtaMinutes(),
                BookingService.SEAT_HOLD_MINUTES, base.routeNote(),
                overviewPolyline(route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng())
        );
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

        List<Booking> stops = bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> MANIFEST_STOP_STATUSES.contains(b.getStatus()))
                .filter(b -> b.getPickupLat() != null && b.getPickupLng() != null)
                .toList();

        if (stops.isEmpty() || stops.size() > MAX_MULTI_STOP_WAYPOINTS) {
            if (stops.size() > MAX_MULTI_STOP_WAYPOINTS) {
                log.warn("Route {} has {} active pickups, over the {}-waypoint cap - falling back to a direct origin->destination preview",
                        routePostId, stops.size(), MAX_MULTI_STOP_WAYPOINTS);
            }
            RoutePreview direct = mapsService.getRoutePreview(route.getOriginLat(), route.getOriginLng(),
                    route.getDestinationLat(), route.getDestinationLng());
            return new MultiStopRoutePreview(direct.distanceKm(), direct.durationMinutes(), direct.overviewPolyline(), List.of());
        }

        List<double[]> waypointCoords = stops.stream().map(b -> new double[]{b.getPickupLat(), b.getPickupLng()}).toList();
        DirectionsMultiStopResult result = mapsService.getMultiStopRoutePreview(
                route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng(), waypointCoords);

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
                    driver != null && driver.verificationStatus() == VerificationStatus.APPROVED,
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
            return mapsService.getDistanceMatrixBatch(riderLat, riderLng, origins).stream()
                    .map(DistanceMatrixResult::durationMinutes)
                    .toList();
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

    public NearbyActivityResponse getNearbyActivity(double lat, double lng, double radiusKm) {
        List<RoutePost> nearby = routePostRepository.findNearbyOrigins(lat, lng, radiusKm, Instant.now());
        List<GeoPin> pins = nearby.stream()
                .map(r -> new GeoPin(r.getOriginLat(), r.getOriginLng()))
                .toList();
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
        route.setStatus(RouteStatus.IN_PROGRESS);
        route.setStartedAt(Instant.now());
        routePostRepository.save(route);
    }

    /** Anything not already a terminal state - HELD/CONFIRMED plus the trip-in-progress phases, all of which a driver-side route cancellation must still unwind and refund. */
    private static final List<BookingStatus> CANCELLABLE_BY_ROUTE_CANCELLATION = List.of(
            BookingStatus.HELD, BookingStatus.CONFIRMED,
            BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);

    @Transactional
    public void cancel(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can cancel it");
        }

        route.setStatus(RouteStatus.CANCELLED);
        routePostRepository.save(route);

        List<Booking> affected = bookingRepository.findByRoutePostId(routePostId);
        List<String> affectedIds = affected.stream().map(BaseEntity::getId).toList();

        for (Booking booking : affected) {
            if (CANCELLABLE_BY_ROUTE_CANCELLATION.contains(booking.getStatus())) {
                booking.setStatus(BookingStatus.CANCELLED_DRIVER);
                bookingRepository.save(booking);
                kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                        .bookingId(booking.getId())
                        .routePostId(routePostId)
                        .riderId(booking.getRiderId())
                        .cancelledBy("DRIVER")
                        .feeApplied(false)
                        .cancelledAt(LocalDateTime.now())
                        .build());
            }
        }

        kafkaTemplate.send(KafkaTopics.ROUTE_CANCELLED, RouteCancelledEvent.builder()
                .routePostId(routePostId)
                .driverId(route.getDriverId())
                .affectedBookingIds(affectedIds)
                .cancelledAt(LocalDateTime.now())
                .build());
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

    private static Instant startOfToday() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
