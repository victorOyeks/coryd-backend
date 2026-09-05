package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RatingDirection;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.enums.TripHistoryStatus;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.BookingConfirmedEvent;
import org.coryd.common.kafka.events.DriverArrivedEvent;
import org.coryd.common.kafka.events.DriverEnRouteEvent;
import org.coryd.common.kafka.events.EmergencyContactPayload;
import org.coryd.common.kafka.events.IssueReportedEvent;
import org.coryd.common.kafka.events.SosTriggeredEvent;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.common.kafka.events.TripShareRequestedEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.CancellationPolicy;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.BookingDetailsResponse;
import org.coryd.trip.dto.BookingStatusHistoryEntry;
import org.coryd.trip.dto.BookingSummary;
import org.coryd.trip.dto.DriverActiveTripResponse;
import org.coryd.trip.dto.DriverTripHistoryItem;
import org.coryd.trip.dto.DriverTripStatsResponse;
import org.coryd.trip.dto.IssueReportRequest;
import org.coryd.trip.dto.RateRequest;
import org.coryd.trip.dto.RatingSummaryResponse;
import org.coryd.trip.dto.ReceiptPassenger;
import org.coryd.trip.dto.SharedTripResponse;
import org.coryd.trip.dto.TripHistoryItem;
import org.coryd.trip.dto.TripReceiptResponse;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.BookingStatusHistory;
import org.coryd.trip.entity.IssueReport;
import org.coryd.trip.entity.Rating;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SosIncident;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.BookingStatusHistoryRepository;
import org.coryd.trip.repository.IssueReportRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SosIncidentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    /** Public so BookingDetailsResponse/RideDetailsResponse can reference the same value the "seat held for X min" copy shown to riders describes. */
    public static final int SEAT_HOLD_MINUTES = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingRepository bookingRepository;
    private final BookingStatusHistoryRepository historyRepository;
    private final RoutePostRepository routePostRepository;
    private final RatingRepository ratingRepository;
    private final SosIncidentRepository sosIncidentRepository;
    private final IssueReportRepository issueReportRepository;
    private final CancellationPolicy cancellationPolicy;
    private final ProfileServiceClient profileServiceClient;
    private final IdentityServiceClient identityServiceClient;
    private final ShareTokenService shareTokenService;
    private final LocationService locationService;
    private final MapsService mapsService;
    private final ChatService chatService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final int SHARE_LINK_VALID_HOURS = 4;

    /** Last 5 bookings, newest first - powers the Home screen's "Recent Routes" list. */
    public List<Booking> getRecentRoutesForRider(String riderId) {
        return bookingRepository.findTop5ByRiderIdOrderByCreatedAtDesc(riderId);
    }

    private static final List<BookingStatus> ACTIVE_TRIP_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);
    private static final List<BookingStatus> CANCELLED_TRIP_STATUSES =
            List.of(BookingStatus.CANCELLED_RIDER, BookingStatus.CANCELLED_DRIVER, BookingStatus.NO_SHOW);
    /** Every status that belongs in Trip History at all - HELD bookings never got confirmed, so they're excluded. */
    private static final List<BookingStatus> ALL_HISTORY_STATUSES = Stream.of(
                    ACTIVE_TRIP_STATUSES.stream(), Stream.of(BookingStatus.COMPLETED), CANCELLED_TRIP_STATUSES.stream())
            .flatMap(s -> s).toList();

    /** Powers the Home screen's "Go back to your current trip" banner - empty if the rider has no trip in flight. */
    public Optional<BookingDetailsResponse> getActiveBookingForRider(String riderId) {
        return bookingRepository.findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(riderId, ACTIVE_TRIP_STATUSES)
                .map(booking -> getBookingDetails(booking.getId()));
    }

    /** Dashboard's "Active Trip" card - the driver's soonest not-yet-departed route, empty if they have none posted. Returns everything trip-service has on it (route + full booking list), not just the card's headline count, so the client can also build a tap-through detail view from this one call. */
    public Optional<DriverActiveTripResponse> getActiveTripForDriver(String driverId) {
        List<RoutePost> candidates = routePostRepository.findByDriverIdAndStatusInAndDepartureTimeAfterOrderByDepartureTimeAsc(
                driverId, List.of(RouteStatus.PUBLISHED, RouteStatus.FULL, RouteStatus.IN_PROGRESS), startOfToday());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        RoutePost route = candidates.get(0);
        List<Booking> activeBookings = bookingRepository.findByRoutePostId(route.getId()).stream()
                .filter(b -> ACTIVE_TRIP_STATUSES.contains(b.getStatus()))
                .toList();

        Map<String, RatingRepository.RatingSummary> riderRatings = riderRatingsFor(activeBookings);
        Map<String, ProfileServiceClient.RiderSummaryItem> riders = ridersFor(activeBookings);
        List<BookingSummary> bookingSummaries = activeBookings.stream()
                .map(b -> toSummary(b, riderRatings, riders))
                .toList();
        int seatsBooked = activeBookings.stream().mapToInt(Booking::getSeatCount).sum();

        return Optional.of(new DriverActiveTripResponse(
                route.getId(), route.getVehicleId(),
                route.getOriginLabel(), route.getOriginLat(), route.getOriginLng(),
                route.getDestinationLabel(), route.getDestinationLat(), route.getDestinationLng(),
                route.getDepartureTime(), route.getFarePerSeat(),
                route.getSeatCapacity(), route.getSeatsAvailable(), seatsBooked, route.getStatus(),
                bookingSummaries));
    }

    /** "View Passenger Manifest" after publishing (or any time after) - who's booked on this specific route, only visible to the route's own driver. Excludes only HELD (payment-pending, not a real booking yet) - cancellations and no-shows stay visible so the driver sees the full picture. */
    public List<BookingSummary> getManifestForRoute(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can view its passenger manifest");
        }
        List<Booking> manifest = bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> b.getStatus() != BookingStatus.HELD)
                .toList();
        Map<String, RatingRepository.RatingSummary> riderRatings = riderRatingsFor(manifest);
        Map<String, ProfileServiceClient.RiderSummaryItem> riders = ridersFor(manifest);
        return manifest.stream().map(b -> toSummary(b, riderRatings, riders)).toList();
    }

    /** The Receipt screen - ownership-gated same as the manifest. totalEarned/seatsBooked come from every non-HELD booking regardless of status, same reasoning as the trip-history list; receiptReference is derived deterministically (not random) so the same trip always shows the same reference number, not a new one per view. */
    public TripReceiptResponse getTripReceipt(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can view its receipt");
        }

        List<Booking> booked = bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> b.getStatus() != BookingStatus.HELD)
                .toList();

        List<String> riderIds = booked.stream().map(Booking::getRiderId).distinct().toList();
        List<String> bookingIds = booked.stream().map(Booking::getId).toList();
        Map<String, ProfileServiceClient.RiderSummaryItem> riders = profileServiceClient.fetchRiderSummaries(riderIds);
        Map<String, Rating> ratingsByBooking = ratingRepository.findByBookingIdInAndDirection(bookingIds, RatingDirection.DRIVER_TO_RIDER)
                .stream().collect(Collectors.toMap(Rating::getBookingId, r -> r));

        List<ReceiptPassenger> passengers = booked.stream().map(booking -> {
            ProfileServiceClient.RiderSummaryItem rider = riders.get(booking.getRiderId());
            Rating rating = ratingsByBooking.get(booking.getId());
            return new ReceiptPassenger(
                    booking.getRiderId(),
                    rider == null ? null : rider.fullName(),
                    rider == null ? null : rider.photoUrl(),
                    booking.getStatus(),
                    rating == null ? null : rating.getStars()
            );
        }).toList();

        int seatsBooked = booked.stream().mapToInt(Booking::getSeatCount).sum();
        BigDecimal totalEarned = booked.stream().map(Booking::getFareTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        Integer durationMinutes = (route.getStartedAt() != null && route.getCompletedAt() != null)
                ? (int) Duration.between(route.getStartedAt(), route.getCompletedAt()).toMinutes()
                : null;

        return new TripReceiptResponse(
                route.getId(), route.getStatus(), route.getOriginLabel(), route.getDestinationLabel(),
                route.getDepartureTime(), durationMinutes, passengers,
                route.getFarePerSeat(), seatsBooked, totalEarned, receiptReference(route)
        );
    }

    private static String receiptReference(RoutePost route) {
        String datePart = LocalDate.ofInstant(route.getDepartureTime(), ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String idSuffix = route.getId().replace("-", "").toUpperCase();
        return "CRY-" + datePart + "-" + idSuffix.substring(0, Math.min(6, idSuffix.length()));
    }

    private static BookingSummary toSummary(Booking booking, Map<String, RatingRepository.RatingSummary> riderRatings,
                                             Map<String, ProfileServiceClient.RiderSummaryItem> riders) {
        RatingRepository.RatingSummary rating = riderRatings.get(booking.getRiderId());
        ProfileServiceClient.RiderSummaryItem rider = riders.get(booking.getRiderId());
        return new BookingSummary(booking.getId(), booking.getRiderId(),
                rider == null ? null : rider.fullName(), rider == null ? null : rider.photoUrl(),
                booking.getSeatCount(),
                booking.getFareTotal(), booking.getStatus(), booking.getPickupConfirmedAt(),
                booking.getPickupLabel(), booking.getPickupLat(), booking.getPickupLng(),
                rating == null ? null : rating.getAvgStars(),
                rating == null ? null : rating.getRatingCount());
    }

    private Map<String, ProfileServiceClient.RiderSummaryItem> ridersFor(List<Booking> bookings) {
        List<String> riderIds = bookings.stream().map(Booking::getRiderId).distinct().toList();
        return riderIds.isEmpty() ? Map.of() : profileServiceClient.fetchRiderSummaries(riderIds);
    }

    /** One batched query for every rider on a route, not one per row - same convention as getHistory()'s driver-rating batch. */
    private Map<String, RatingRepository.RatingSummary> riderRatingsFor(List<Booking> bookings) {
        List<String> riderIds = bookings.stream().map(Booking::getRiderId).distinct().toList();
        if (riderIds.isEmpty()) {
            return Map.of();
        }
        return ratingRepository.summarize(riderIds).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, r -> r));
    }

    /** Dashboard's "Trips this week" tile - completed bookings on routes that departed Monday through now. */
    public DriverTripStatsResponse getStatsForDriver(String driverId) {
        List<String> routeIdsThisWeek = routePostRepository
                .findByDriverIdAndDepartureTimeBetween(driverId, startOfWeek(), Instant.now())
                .stream().map(RoutePost::getId).toList();
        long tripsThisWeek = routeIdsThisWeek.isEmpty() ? 0
                : bookingRepository.countByRoutePostIdInAndStatus(routeIdsThisWeek, BookingStatus.COMPLETED);

        BookingRepository.DriverTripCounts counts = bookingRepository.countLifetimeTripsForDriver(driverId);
        long completed = counts.getCompleted() == null ? 0 : counts.getCompleted();
        long cancelledByDriver = counts.getCancelledDriver() == null ? 0 : counts.getCancelledDriver();
        long reliabilityBase = completed + cancelledByDriver;
        Integer reliabilityPercent = reliabilityBase == 0 ? null
                : (int) Math.round(completed * 100.0 / reliabilityBase);

        return new DriverTripStatsResponse((int) tripsThisWeek, completed, reliabilityPercent);
    }

    /** Driver Trip History screen - one row per posted route (not per booking), newest first, optionally filtered by status bucket and searched by origin/destination. No status picked means every route regardless of status (including still-upcoming PUBLISHED/FULL ones), not just the "already happened" ONGOING/COMPLETED/CANCELLED buckets. seatsBooked/totalEarned are summed from every non-HELD booking regardless of the route's own status, so a cancelled route still shows what it actually had booked, not zeros. */
    public PagedResponse<DriverTripHistoryItem> getHistoryForDriver(String driverId, TripHistoryStatus statusBucket, String search, Pageable pageable) {
        List<RouteStatus> statuses = statusBucket == null ? List.of(RouteStatus.values()) : switch (statusBucket) {
            case ONGOING -> List.of(RouteStatus.IN_PROGRESS);
            case COMPLETED -> List.of(RouteStatus.COMPLETED);
            case CANCELLED -> List.of(RouteStatus.CANCELLED);
        };
        String searchTerm = (search == null || search.isBlank()) ? null : search;
        Page<RoutePost> routes = routePostRepository.searchDriverHistory(driverId, statuses, searchTerm, pageable);

        List<String> routePostIds = routes.getContent().stream().map(RoutePost::getId).toList();
        Map<String, List<Booking>> bookingsByRoute = routePostIds.isEmpty() ? Map.of()
                : bookingRepository.findByRoutePostIdIn(routePostIds).stream()
                        .collect(Collectors.groupingBy(Booking::getRoutePostId));

        List<DriverTripHistoryItem> items = routes.getContent().stream().map(route -> {
            List<Booking> booked = bookingsByRoute.getOrDefault(route.getId(), List.of()).stream()
                    .filter(b -> b.getStatus() != BookingStatus.HELD)
                    .toList();
            int seatsBooked = booked.stream().mapToInt(Booking::getSeatCount).sum();
            BigDecimal totalEarned = booked.stream().map(Booking::getFareTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new DriverTripHistoryItem(route.getId(), route.getOriginLabel(), route.getDestinationLabel(),
                    route.getDepartureTime(), route.getStatus(), seatsBooked, totalEarned);
        }).toList();

        return PagedResponse.from(new PageImpl<>(items, pageable, routes.getTotalElements()));
    }

    private static Instant startOfToday() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant startOfWeek() {
        return LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Account screen's star rating - same aggregate query used for driver ratings elsewhere, just keyed on the caller's own id. */
    public RatingSummaryResponse getRatingSummary(String userId) {
        return ratingRepository.summarize(List.of(userId)).stream()
                .findFirst()
                .map(r -> new RatingSummaryResponse(r.getAvgStars(), r.getRatingCount()))
                .orElse(RatingSummaryResponse.NONE);
    }

    /** Trip History screen - null statusBucket means "All". */
    public PagedResponse<TripHistoryItem> getHistory(String riderId, TripHistoryStatus statusBucket, String search, Pageable pageable) {
        List<BookingStatus> statuses = statusBucket == null ? ALL_HISTORY_STATUSES : switch (statusBucket) {
            case ONGOING -> ACTIVE_TRIP_STATUSES;
            case COMPLETED -> List.of(BookingStatus.COMPLETED);
            case CANCELLED -> CANCELLED_TRIP_STATUSES;
        };
        String searchTerm = (search == null || search.isBlank()) ? null : search;
        Page<Booking> page = bookingRepository.findHistory(riderId, statuses, searchTerm, pageable);

        List<String> routePostIds = page.getContent().stream().map(Booking::getRoutePostId).distinct().toList();
        Map<String, RoutePost> routes = routePostRepository.findAllById(routePostIds).stream()
                .collect(Collectors.toMap(RoutePost::getId, r -> r));
        List<String> driverIds = routes.values().stream().map(RoutePost::getDriverId).distinct().toList();
        Map<String, RatingRepository.RatingSummary> ratings = ratingRepository.summarize(driverIds).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, r -> r));

        List<TripHistoryItem> items = page.getContent().stream().map(booking -> {
            RoutePost route = routes.get(booking.getRoutePostId());
            RatingRepository.RatingSummary rating = route == null ? null : ratings.get(route.getDriverId());
            return new TripHistoryItem(
                    booking.getId(), booking.getStatus(),
                    route == null ? null : route.getOriginLabel(),
                    route == null ? null : route.getDestinationLabel(),
                    route == null ? null : route.getDepartureTime(),
                    booking.getFareTotal(),
                    booking.getDriverNameSnapshot(),
                    rating == null ? null : rating.getAvgStars(),
                    rating == null ? null : rating.getRatingCount()
            );
        }).toList();

        return PagedResponse.from(new PageImpl<>(items, pageable, page.getTotalElements()));
    }

    /** Rider taps "Share trip details" - generates the signed link and hands off to notification-service (via Kafka) to actually SMS the emergency contact, not a direct HTTP call. */
    @Transactional
    public String shareTrip(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        List<ProfileServiceClient.RiderContact> contacts = profileServiceClient.fetchRiderContacts(booking.getRiderId());
        if (contacts.isEmpty()) {
            throw new BadRequestException("Add an emergency contact to your profile before sharing a trip");
        }

        Instant expiresAt = route.getDepartureTime().plus(SHARE_LINK_VALID_HOURS, ChronoUnit.HOURS);
        String link = shareTokenService.generateLink(bookingId, expiresAt);

        kafkaTemplate.send(KafkaTopics.TRIP_SHARE_REQUESTED, TripShareRequestedEvent.builder()
                .bookingId(bookingId)
                .riderId(booking.getRiderId())
                .shareLink(link)
                .contacts(toEventPayload(contacts))
                .requestedAt(LocalDateTime.now())
                .build());

        return link;
    }

    /** What a "share trip" link resolves to - no auth, the whole point is the recipient has no account. */
    public SharedTripResponse getSharedTripView(String token) {
        String bookingId = shareTokenService.verifyAndExtractBookingId(token)
                .orElseThrow(() -> new BadRequestException("This trip link has expired or is invalid"));

        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        ProfileServiceClient.DriverSummaryItem driver = profileServiceClient
                .fetchDriverSummaries(List.of(route.getDriverId()))
                .get(route.getDriverId());
        ProfileServiceClient.VehicleSummaryItem vehicle = route.getVehicleId() == null ? null
                : profileServiceClient.fetchVehicleSummaries(List.of(route.getVehicleId())).get(route.getVehicleId());

        return new SharedTripResponse(
                route.getOriginLabel(), route.getDestinationLabel(), route.getDepartureTime(),
                booking.getStatus(),
                driver == null ? booking.getDriverNameSnapshot() : driver.legalName(),
                driver == null ? null : driver.photoUrl(),
                driver != null && driver.verificationStatus() == VerificationStatus.APPROVED,
                vehicle == null ? null : vehicle.make(),
                vehicle == null ? null : vehicle.model(),
                vehicle == null ? booking.getVehiclePlateSnapshot() : vehicle.plateNumber(),
                vehicle != null && vehicle.hasAC(),
                locationService.getLocation(bookingId)
        );
    }

    /** Serves both Pay Now (status=HELD) and Booking Confirmed (status=CONFIRMED) - see BookingDetailsResponse. */
    public BookingDetailsResponse getBookingDetails(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        ProfileServiceClient.DriverSummaryItem driver = profileServiceClient
                .fetchDriverSummaries(List.of(route.getDriverId()))
                .get(route.getDriverId());
        ProfileServiceClient.VehicleSummaryItem vehicle = route.getVehicleId() == null ? null
                : profileServiceClient.fetchVehicleSummaries(List.of(route.getVehicleId())).get(route.getVehicleId());
        String driverPhone = identityServiceClient.fetchPhone(route.getDriverId());

        return new BookingDetailsResponse(
                booking.getId(), booking.getRoutePostId(), booking.getStatus(),
                route.getOriginLabel(), route.getDestinationLabel(),
                route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng(),
                route.getDepartureTime(),
                booking.getSeatCount(), booking.getFareTotal(), booking.getFarePerSeatSnapshot(),
                booking.getHoldExpiresAt(), SEAT_HOLD_MINUTES,
                route.getDriverId(),
                driver == null ? booking.getDriverNameSnapshot() : driver.legalName(),
                driverPhone,
                driver == null ? null : driver.photoUrl(),
                driver != null && driver.verificationStatus() == VerificationStatus.APPROVED,
                vehicle == null ? null : vehicle.make(),
                vehicle == null ? null : vehicle.model(),
                vehicle == null ? booking.getVehiclePlateSnapshot() : vehicle.plateNumber(),
                vehicle != null && vehicle.hasAC(),
                booking.getStatus() == BookingStatus.COMPLETED ? computeDurationMinutes(bookingId) : null,
                overviewPolyline(route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng())
        );
    }

    /** Best-effort, same "a Maps hiccup shouldn't take down the screen" pattern used elsewhere - a Directions failure means no line on the map, not a broken Pay Now/Booking Confirmed screen. Cached 7 days in MapsService keyed by rounded coordinates. */
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

    /** Track Live Trip's status timeline - kept off BookingDetailsResponse since Pay Now/Booking Confirmed don't render it. */
    public List<BookingStatusHistoryEntry> getStatusHistory(String bookingId) {
        return historyRepository.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(h -> new BookingStatusHistoryEntry(h.getStatus(), h.getCreatedAt()))
                .toList();
    }

    /** Receipt screen's trip duration - the gap between "trip in progress" and "trip completed" in the status history, not stored anywhere directly. */
    private Integer computeDurationMinutes(String bookingId) {
        List<BookingStatusHistory> history = historyRepository.findByBookingIdOrderByCreatedAtAsc(bookingId);
        Instant startedAt = history.stream()
                .filter(h -> h.getStatus() == BookingStatus.IN_PROGRESS)
                .map(BookingStatusHistory::getCreatedAt).findFirst().orElse(null);
        Instant completedAt = history.stream()
                .filter(h -> h.getStatus() == BookingStatus.COMPLETED)
                .map(BookingStatusHistory::getCreatedAt).findFirst().orElse(null);
        if (startedAt == null || completedAt == null) {
            return null;
        }
        return (int) Duration.between(startedAt, completedAt).toMinutes();
    }

    @Transactional
    public Booking reserveSeats(String routePostId, BookSeatsRequest request) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        if (bookingRepository.findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(request.riderId(), ACTIVE_TRIP_STATUSES).isPresent()) {
            throw new BadRequestException("You already have a booked or ongoing trip - complete or cancel it before booking another");
        }

        int updated = routePostRepository.holdSeats(routePostId, request.seatCount());
        if (updated == 0) {
            throw new BadRequestException("Not enough seats available");
        }
        routePostRepository.markFullIfExhausted(routePostId);
        routePostRepository.lockOnFirstBooking(routePostId, Instant.now());

        BigDecimal fareTotal = route.getFarePerSeat().multiply(BigDecimal.valueOf(request.seatCount()));

        Booking booking = Booking.builder()
                .routePostId(routePostId)
                .riderId(request.riderId())
                .seatCount(request.seatCount())
                .fareTotal(fareTotal)
                .status(BookingStatus.HELD)
                .holdExpiresAt(Instant.now().plus(SEAT_HOLD_MINUTES, ChronoUnit.MINUTES))
                .driverNameSnapshot(profileServiceClient.fetchDriverName(route.getDriverId()))
                .vehiclePlateSnapshot(profileServiceClient.fetchVehiclePlate(route.getVehicleId()))
                .routeSummarySnapshot(route.getOriginLabel() + " -> " + route.getDestinationLabel())
                .farePerSeatSnapshot(route.getFarePerSeat())
                .pickupLabel(request.pickupLabel())
                .pickupLat(request.pickupLat())
                .pickupLng(request.pickupLng())
                .build();
        bookingRepository.save(booking);
        recordHistory(booking.getId(), BookingStatus.HELD, "Seat held pending payment");

        return booking;
    }

    /**
     * PAYMENT_SUCCEEDED can arrive well after the 4-minute hold window if a Paystack webhook is
     * delayed - by then SeatHoldExpiryJob may have already released the seat and cancelled the
     * booking out from under it. Blindly setting CONFIRMED here would resurrect a booking whose
     * seat may already be back in inventory (or resold to someone else), with no seat re-hold to
     * back it up. So this only ever confirms a booking that's still HELD; anything else means the
     * rider's payment succeeded for a reservation that's already gone, and the right outcome is a
     * refund, not a fake confirmation - reusing the existing cancellation-refund pipeline (payment-
     * service's EarningsService.refund() is keyed off bookingId, not the booking's current status,
     * so republishing BookingCancelledEvent here is enough to trigger it).
     */
    @Transactional
    public void confirm(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return;
        }
        if (booking.getStatus() != BookingStatus.HELD) {
            log.warn("Payment succeeded for booking {} but it's no longer HELD (status={}) - seat hold must have "
                            + "already expired before the webhook arrived. Refunding instead of confirming.",
                    bookingId, booking.getStatus());
            kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                    .bookingId(booking.getId())
                    .routePostId(booking.getRoutePostId())
                    .riderId(booking.getRiderId())
                    .cancelledBy("SYSTEM")
                    .feeApplied(false)
                    .cancelledAt(LocalDateTime.now())
                    .build());
            return;
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.CONFIRMED, "Payment succeeded");

        kafkaTemplate.send(KafkaTopics.BOOKING_CONFIRMED, BookingConfirmedEvent.builder()
                .bookingId(booking.getId())
                .routePostId(booking.getRoutePostId())
                .riderId(booking.getRiderId())
                .seatCount(booking.getSeatCount())
                .fareTotal(booking.getFareTotal())
                .confirmedAt(LocalDateTime.now())
                .build());
    }

    @Transactional
    public void releaseOnPaymentFailure(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        routePostRepository.releaseSeats(booking.getRoutePostId(), booking.getSeatCount());
        booking.setStatus(BookingStatus.CANCELLED_RIDER);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.CANCELLED_RIDER, "Payment failed - seat released");
    }

    @Transactional
    public void cancelByRider(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        if (!callerId.equals(booking.getRiderId())) {
            throw new UnauthorizedException("Only this booking's rider can cancel it");
        }
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        boolean feeApplies = cancellationPolicy.isFeeApplicable(route.getDepartureTime(), Instant.now());
        routePostRepository.releaseSeats(booking.getRoutePostId(), booking.getSeatCount());

        booking.setStatus(BookingStatus.CANCELLED_RIDER);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.CANCELLED_RIDER, feeApplies ? "Cancelled inside free window" : "Cancelled free of charge");

        kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                .bookingId(booking.getId())
                .routePostId(booking.getRoutePostId())
                .riderId(booking.getRiderId())
                .driverId(route.getDriverId())
                .cancelledBy("RIDER")
                .feeApplied(feeApplies)
                .cancelledAt(LocalDateTime.now())
                .build());
    }

    /** Driver taps "I'm on my way" - Track Live Trip's "Driver en route to pickup" phase. */
    @Transactional
    public void markEnRouteToPickup(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can mark it en route");
        }
        booking.setStatus(BookingStatus.EN_ROUTE_TO_PICKUP);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.EN_ROUTE_TO_PICKUP, "Driver en route to pickup");

        kafkaTemplate.send(KafkaTopics.DRIVER_EN_ROUTE, DriverEnRouteEvent.builder()
                .bookingId(bookingId)
                .riderId(booking.getRiderId())
                .driverName(booking.getDriverNameSnapshot())
                .occurredAt(LocalDateTime.now())
                .build());
    }

    /** Driver taps "I've arrived" - Track Live Trip's "Driver at pickup point" phase. */
    @Transactional
    public void markArrivedAtPickup(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can mark it arrived at pickup");
        }
        booking.setStatus(BookingStatus.AT_PICKUP);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.AT_PICKUP, "Driver arrived at pickup point");

        kafkaTemplate.send(KafkaTopics.DRIVER_ARRIVED, DriverArrivedEvent.builder()
                .bookingId(bookingId)
                .riderId(booking.getRiderId())
                .driverName(booking.getDriverNameSnapshot())
                .occurredAt(LocalDateTime.now())
                .build());
    }

    /** Rider taps "I've boarded", or the driver taps "Mark Picked Up" on the Passenger Manifest - either way advances status to IN_PROGRESS, not just a timestamp, so it drives the Track Live Trip phase timeline too. */
    @Transactional
    public void markPickedUp(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(booking.getRiderId()) && !callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this booking's rider or the route's driver can mark it picked up");
        }
        booking.setPickupConfirmedAt(Instant.now());
        booking.setStatus(BookingStatus.IN_PROGRESS);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.IN_PROGRESS, "Rider picked up - trip in progress");
    }

    @Transactional
    public void markNoShow(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can mark it as a no-show");
        }
        booking.setStatus(BookingStatus.NO_SHOW);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.NO_SHOW, "Rider did not show up within the grace period");
    }

    /** Undoes a manifest mark (Picked Up or No Show) made in error, before the trip is completed - powers the manifest's "No Show x" undo chip. */
    @Transactional
    public void undoManifestMark(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can reset a manifest mark");
        }
        if (route.getStatus() == RouteStatus.COMPLETED) {
            throw new BadRequestException("This trip is already completed - manifest marks can no longer be changed");
        }
        if (booking.getStatus() != BookingStatus.IN_PROGRESS && booking.getStatus() != BookingStatus.NO_SHOW) {
            throw new BadRequestException("This booking hasn't been marked yet");
        }
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setPickupConfirmedAt(null);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.CONFIRMED, "Manifest mark reset by driver");
    }

    /** "Reset all Manifest" - undoes every Picked Up/No Show mark on the route in one call. */
    @Transactional
    public void resetManifest(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can reset its manifest");
        }
        if (route.getStatus() == RouteStatus.COMPLETED) {
            throw new BadRequestException("This trip is already completed - manifest marks can no longer be changed");
        }
        bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> b.getStatus() == BookingStatus.IN_PROGRESS || b.getStatus() == BookingStatus.NO_SHOW)
                .forEach(b -> {
                    b.setStatus(BookingStatus.CONFIRMED);
                    b.setPickupConfirmedAt(null);
                    bookingRepository.save(b);
                    recordHistory(b.getId(), BookingStatus.CONFIRMED, "Manifest mark reset by driver");
                });
    }

    @Transactional
    public void completeTrip(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can complete it");
        }
        completeBookingInternal(booking, route);
    }

    /** Statuses that still need a Picked Up/No Show decision before a route-level Complete Trip is allowed. */
    private static final List<BookingStatus> UNRESOLVED_MANIFEST_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP);

    /** "Complete Trip" button - completes every picked-up passenger on the route in one call, gated on every booking having been marked Picked Up or No Show first (the manifest's "Mark all passengers before completing" banner). No-shows are left as-is; they never had a trip to complete or settle. */
    @Transactional
    public List<BookingSummary> completeRoute(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can complete it");
        }
        if (route.getStatus() != RouteStatus.IN_PROGRESS) {
            throw new BadRequestException("This trip hasn't been started yet");
        }

        List<Booking> bookings = bookingRepository.findByRoutePostId(routePostId);
        boolean anyUnresolved = bookings.stream().anyMatch(b -> UNRESOLVED_MANIFEST_STATUSES.contains(b.getStatus()));
        if (anyUnresolved) {
            throw new BadRequestException("Mark all passengers before completing this trip");
        }

        for (Booking booking : bookings) {
            if (booking.getStatus() == BookingStatus.IN_PROGRESS) {
                completeBookingInternal(booking, route);
            }
        }

        route.setStatus(RouteStatus.COMPLETED);
        route.setCompletedAt(Instant.now());
        routePostRepository.save(route);

        // The Trip Group Chat's whole purpose ends with the trip - delete its conversation outright, not just mark it inactive.
        chatService.deleteChatForRoute(routePostId);

        List<Booking> finalBookings = bookingRepository.findByRoutePostId(routePostId);
        Map<String, RatingRepository.RatingSummary> riderRatings = riderRatingsFor(finalBookings);
        Map<String, ProfileServiceClient.RiderSummaryItem> riders = ridersFor(finalBookings);
        return finalBookings.stream().map(b -> toSummary(b, riderRatings, riders)).toList();
    }

    private void completeBookingInternal(Booking booking, RoutePost route) {
        booking.setStatus(BookingStatus.COMPLETED);
        bookingRepository.save(booking);
        recordHistory(booking.getId(), BookingStatus.COMPLETED, "Trip completed");

        kafkaTemplate.send(KafkaTopics.TRIP_COMPLETED, TripCompletedEvent.builder()
                .bookingId(booking.getId())
                .routePostId(booking.getRoutePostId())
                .riderId(booking.getRiderId())
                .driverId(route.getDriverId())
                .fareTotal(booking.getFareTotal())
                .completedAt(LocalDateTime.now())
                .build());
    }

    @Transactional
    public Rating rate(String bookingId, RateRequest request) {
        getOrThrow(bookingId); // validates the booking exists before accepting a rating
        Rating rating = Rating.builder()
                .bookingId(bookingId)
                .raterId(request.raterId())
                .rateeId(request.rateeId())
                .direction(request.direction())
                .stars(request.stars())
                .comment(request.comment())
                .build();
        return ratingRepository.save(rating);
    }

    @Transactional
    public SosIncident triggerSos(String bookingId, String triggeredBy, Double latitude, Double longitude) {
        Booking booking = getOrThrow(bookingId);

        SosIncident incident = sosIncidentRepository.save(SosIncident.builder()
                .bookingId(bookingId)
                .triggeredBy(triggeredBy)
                .latitude(latitude)
                .longitude(longitude)
                .opsNotifiedAt(Instant.now())
                .build());

        // Every emergency contact the rider has saved, regardless of who triggered SOS - it's a
        // rider-safety feature. Looked up here (not in notification-service) since this service
        // already has the profile-service client for Share Trip - no new cross-service
        // dependency needed there.
        List<ProfileServiceClient.RiderContact> contacts = profileServiceClient.fetchRiderContacts(booking.getRiderId());

        // Fan-out only, never the primary path - the incident is already saved above.
        kafkaTemplate.send(KafkaTopics.SOS_TRIGGERED, SosTriggeredEvent.builder()
                .incidentId(incident.getId())
                .bookingId(bookingId)
                .triggeredBy(triggeredBy)
                .latitude(latitude)
                .longitude(longitude)
                .triggeredAt(LocalDateTime.now())
                .contacts(toEventPayload(contacts))
                .build());

        return incident;
    }

    private static List<EmergencyContactPayload> toEventPayload(List<ProfileServiceClient.RiderContact> contacts) {
        return contacts.stream()
                .map(c -> EmergencyContactPayload.builder().name(c.name()).phone(c.phone()).build())
                .toList();
    }

    @Transactional
    public IssueReport reportIssue(IssueReportRequest request) {
        String referenceNumber = "ISS-" + String.format("%08d", RANDOM.nextInt(100_000_000));
        IssueReport issue = issueReportRepository.save(IssueReport.builder()
                .bookingId(request.bookingId())
                .reportedBy(request.reportedBy())
                .category(request.category())
                .description(request.description())
                .referenceNumber(referenceNumber)
                .status("OPEN")
                .build());

        kafkaTemplate.send(KafkaTopics.ISSUE_REPORTED, IssueReportedEvent.builder()
                .issueId(issue.getId())
                .bookingId(request.bookingId())
                .reportedBy(request.reportedBy())
                .category(request.category())
                .referenceNumber(referenceNumber)
                .reportedAt(LocalDateTime.now())
                .build());

        return issue;
    }

    private Booking getOrThrow(String bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
    }

    private void recordHistory(String bookingId, BookingStatus status, String note) {
        historyRepository.save(BookingStatusHistory.builder()
                .bookingId(bookingId)
                .status(status)
                .note(note)
                .build());
    }
}
