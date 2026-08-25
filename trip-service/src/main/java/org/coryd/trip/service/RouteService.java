package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
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
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.dto.GeoPin;
import org.coryd.trip.dto.NearbyActivityResponse;
import org.coryd.trip.dto.PostRouteRequest;
import org.coryd.trip.dto.RideDetailsResponse;
import org.coryd.trip.dto.RideSearchResult;
import org.coryd.trip.dto.RoutePreview;
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
import org.coryd.trip.util.GeoUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RouteService {

    private static final int NEARBY_OPTIONS_LIMIT = 5;
    private static final double NEARBY_OPTIONS_RADIUS_MULTIPLIER = 3.0;
    private static final int NEARBY_OPTIONS_TIME_WINDOW_HOURS = 24;

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
        // Keeps getActiveTripForDriver() honestly single-valued rather than needing to
        // pick a winner among several - a driver runs one route at a time, and this
        // self-heals daily since it's the same startOfToday() boundary as that read.
        boolean hasActiveTrip = !routePostRepository.findByDriverIdAndStatusInAndDepartureTimeAfterOrderByDepartureTimeAsc(
                driverId, List.of(RouteStatus.PUBLISHED, RouteStatus.FULL), startOfToday()).isEmpty();
        if (hasActiveTrip) {
            throw new BadRequestException("You already have an active route - it must depart and complete, or be cancelled, before you can post another");
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

    /** The Ride Details screen - one enriched route plus the full route preview (distance/duration/via-summary), not just the rider-to-pickup figure enrich() already covers. */
    public RideDetailsResponse getRouteDetails(String routePostId, double riderLat, double riderLng) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        RideSearchResult base = enrich(List.of(route), riderLat, riderLng).getFirst();
        RoutePreview preview = mapsService.getRoutePreview(
                route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng());
        Instant estimatedArrivalTime = route.getDepartureTime().plus(preview.durationMinutes(), ChronoUnit.MINUTES);

        return new RideDetailsResponse(
                base.routePostId(), base.originLabel(), base.destinationLabel(), base.departureTime(),
                base.farePerSeat(), base.seatsAvailable(), base.driverId(), base.driverName(), base.driverPhotoUrl(),
                base.driverVerified(), base.vehicleMake(), base.vehicleModel(), base.vehiclePlate(), base.vehicleHasAC(),
                base.ratingAvg(), base.ratingCount(), base.pickupEtaMinutes(),
                preview.distanceKm(), preview.durationMinutes(), preview.durationText(), preview.viaSummary(),
                estimatedArrivalTime, BookingService.SEAT_HOLD_MINUTES
        );
    }

    /** Batch-joins driver/vehicle/rating data onto raw route matches - three calls total for the whole page, never one per row. */
    private List<RideSearchResult> enrich(List<RoutePost> routes, double riderLat, double riderLng) {
        List<String> driverIds = routes.stream().map(RoutePost::getDriverId).distinct().toList();
        List<String> vehicleIds = routes.stream().map(RoutePost::getVehicleId).filter(Objects::nonNull).distinct().toList();

        Map<String, ProfileServiceClient.DriverSummaryItem> drivers = profileServiceClient.fetchDriverSummaries(driverIds);
        Map<String, ProfileServiceClient.VehicleSummaryItem> vehicles = profileServiceClient.fetchVehicleSummaries(vehicleIds);
        Map<String, RatingRepository.RatingSummary> ratings = ratingRepository.summarize(driverIds).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, r -> r));

        return routes.stream().map(route -> {
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
                    GeoUtils.estimateMinutes(riderLat, riderLng, route.getOriginLat(), route.getOriginLng())
            );
        }).toList();
    }

    /** "My Routes" / dashboard repost card - newest first, no ownership check since this is a read (mutations are what's gated). */
    public List<RoutePost> findByDriver(String driverId) {
        return routePostRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
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

    private String corridorKey(String originLabel, String destinationLabel) {
        return (originLabel + "->" + destinationLabel).toLowerCase();
    }

    private static Instant startOfToday() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
