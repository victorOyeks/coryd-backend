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
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.config.policy.CancellationPolicy;
import org.coryd.trip.config.policy.IdentityVerificationPolicy;
import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.dto.AcceptReservationRequest;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.BookingPaymentView;
import org.coryd.trip.dto.BookingRouteSummary;
import org.coryd.trip.dto.BookingDetailsResponse;
import org.coryd.trip.event.RouteSearchRelevantChangeEvent;
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
import org.coryd.trip.event.UserSuspensionEventListener;
import org.coryd.trip.dto.TripHistoryItem;
import org.coryd.trip.dto.TripReceiptResponse;
import org.coryd.trip.dto.UpcomingRideItem;
import org.coryd.trip.dto.UpcomingRouteItem;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.DriverVerificationCache;
import org.coryd.trip.entity.BookingStatusHistory;
import org.coryd.trip.entity.IssueReport;
import org.coryd.trip.entity.Rating;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SosIncident;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.BookingStatusHistoryRepository;
import org.coryd.trip.repository.IssueReportRepository;
import org.coryd.trip.repository.DriverVerificationCacheRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.IdentityVerificationCacheRepository;
import org.coryd.trip.repository.SosIncidentRepository;
import org.coryd.trip.repository.SuspendedUserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import java.util.Comparator;
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
    private final DriverVerificationCacheRepository driverVerificationCacheRepository;
    private final SosIncidentRepository sosIncidentRepository;
    private final SuspendedUserRepository suspendedUserRepository;
    private final UserBlockService userBlockService;
    private final IdentityVerificationCacheRepository identityVerificationCacheRepository;
    private final IdentityVerificationPolicy identityVerificationPolicy;
    private final RouteMatcher routeMatcher;
    private final MatchingProperties matchingProperties;
    private final IssueReportRepository issueReportRepository;
    private final CancellationPolicy cancellationPolicy;
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

    private final ProfileServiceClient profileServiceClient;
    private final IdentityServiceClient identityServiceClient;
    private final ShareTokenService shareTokenService;
    private final LocationService locationService;
    private final MapsService mapsService;
    private final ChatService chatService;
    private final RouteService routeService;
    private final TripScheduleService tripScheduleService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;

    private static final int SHARE_LINK_VALID_HOURS = 4;
    /** A driver can raise an SOS or share their trip until it ends. */
    private static final List<RouteStatus> SOS_ROUTE_STATUSES = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL, RouteStatus.IN_PROGRESS);

    /** Last 5 bookings, newest first - powers the Home screen's "Recent Routes" list. */
    public List<Booking> getRecentRoutesForRider(String riderId, String callerId) {
        requireSelf(riderId, callerId);
        return bookingRepository.findTop5ByRiderIdAndConfirmedAtIsNotNullOrderByCreatedAtDesc(riderId);
    }

    private static final List<BookingStatus> ACTIVE_TRIP_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);
    /** Bookings the driver is carrying or has carried - what a route's seatsBooked/totalEarned are made of.
     * Cancellations and no-shows paid the driver nothing, and unpaid holds never were bookings. */
    private static final List<BookingStatus> CARRIED_STATUSES = Stream.concat(
            ACTIVE_TRIP_STATUSES.stream(), Stream.of(BookingStatus.COMPLETED)).toList();
    private static final List<BookingStatus> RIDER_CANCELLABLE_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP);
    private static final List<BookingStatus> AWAITING_PICKUP_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP);
    private static final List<RouteStatus> FINISHED_ROUTE_STATUSES = List.of(RouteStatus.CANCELLED, RouteStatus.COMPLETED);
    /** Codes tried before the driver is locked out and the rider has to confirm boarding themselves. */
    static final int MAX_PICKUP_CODE_ATTEMPTS = 5;
    private static final List<BookingStatus> CANCELLED_TRIP_STATUSES =
            List.of(BookingStatus.CANCELLED_RIDER, BookingStatus.CANCELLED_DRIVER, BookingStatus.NO_SHOW);
    /** Every status that belongs in Trip History at all - HELD bookings never got confirmed, so they're excluded. */
    private static final List<BookingStatus> ALL_HISTORY_STATUSES = Stream.of(
                    ACTIVE_TRIP_STATUSES.stream(), Stream.of(BookingStatus.COMPLETED), CANCELLED_TRIP_STATUSES.stream())
            .flatMap(s -> s).toList();

    /** Home's trip mode - the rider's soonest booking that is under way or departs within the active
     * window. A ride booked for later doesn't count; that's getUpcomingForRider(). */
    public Optional<BookingDetailsResponse> getActiveBookingForRider(String riderId, String callerId) {
        requireSelf(riderId, callerId);
        Instant now = Instant.now();
        return seatsWithRoutes(riderId).stream()
                .filter(s -> s.booking().getStatus() != BookingStatus.CONFIRMED || TripScheduleService.isActive(s.route(), now))
                .min(Comparator.comparing(s -> s.route().getDepartureTime()))
                .map(s -> buildDetails(s.booking(), true));
    }

    /** Home's "Upcoming trips" card - paid rides still outside the active window, soonest first. */
    public List<UpcomingRideItem> getUpcomingForRider(String riderId) {
        Instant now = Instant.now();
        return seatsWithRoutes(riderId).stream()
                .filter(s -> s.booking().getStatus() == BookingStatus.CONFIRMED && !TripScheduleService.isActive(s.route(), now))
                .sorted(Comparator.comparing(s -> s.route().getDepartureTime()))
                .map(s -> new UpcomingRideItem(s.booking().getId(), s.route().getId(), s.booking().getStatus(),
                        s.route().getOriginLabel(), s.route().getDestinationLabel(), s.route().getDepartureTime(),
                        s.booking().getSeatCount(), s.booking().getFareTotal(), s.booking().getPickupLabel(),
                        s.booking().getDriverNameSnapshot(), s.booking().getVehiclePlateSnapshot()))
                .toList();
    }

    private record SeatOnRoute(Booking booking, RoutePost route) {
    }

    private List<SeatOnRoute> seatsWithRoutes(String riderId) {
        List<Booking> seats = bookingRepository.findByRiderIdAndStatusIn(riderId, ACTIVE_TRIP_STATUSES);
        if (seats.isEmpty()) {
            return List.of();
        }
        Map<String, RoutePost> routes = routePostRepository.findAllById(seats.stream().map(Booking::getRoutePostId).distinct().toList())
                .stream().collect(Collectors.toMap(RoutePost::getId, r -> r));

        return seats.stream()
                .filter(b -> routes.containsKey(b.getRoutePostId()))
                .map(b -> new SeatOnRoute(b, routes.get(b.getRoutePostId())))
                .toList();
    }

    /** The RESERVED counterpart to getActiveBookingForRider() above - lets the app proactively show
     * "your favourite driver reserved you a seat" on Home for a rider who missed the push (or didn't
     * tap it), without already knowing a bookingId. An unanswered offer is never a trip the rider is
     * "on", so it stays out of the active/upcoming lists. */
    public Optional<BookingDetailsResponse> getReservedSeatForRider(String riderId) {
        return bookingRepository.findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(riderId, List.of(BookingStatus.RESERVED))
                .map(booking -> buildDetails(booking, true));
    }

    /** Dashboard's "Live - Active Trip" card - the driver's route that is under way or departs within the
     * active window, empty otherwise. Routes scheduled for later are getUpcomingRoutesForDriver(). Returns
     * the route plus its full booking list, so the client can build the passenger screen from this one call. */
    public Optional<DriverActiveTripResponse> getActiveTripForDriver(String driverId, String callerId) {
        requireSelf(driverId, callerId);
        Instant now = Instant.now();
        Optional<RoutePost> active = routePostRepository.findByDriverIdAndStatusIn(driverId, TripScheduleService.LIVE_ROUTE_STATUSES).stream()
                .filter(r -> TripScheduleService.isActive(r, now))
                .min(Comparator.comparing(RoutePost::getDepartureTime));
        if (active.isEmpty()) {
            return Optional.empty();
        }
        RoutePost route = active.get();
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

    /** Driver Home's "Upcoming routes" - published routes still outside the active window, soonest first.
     * Tapping one opens the existing passenger manifest. */
    public List<UpcomingRouteItem> getUpcomingRoutesForDriver(String driverId) {
        Instant now = Instant.now();
        List<RoutePost> upcoming = routePostRepository.findByDriverIdAndStatusIn(driverId, List.of(RouteStatus.PUBLISHED, RouteStatus.FULL)).stream()
                .filter(r -> !TripScheduleService.isActive(r, now))
                .sorted(Comparator.comparing(RoutePost::getDepartureTime))
                .toList();
        if (upcoming.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> seatsBooked = bookingRepository.findByRoutePostIdIn(upcoming.stream().map(RoutePost::getId).toList()).stream()
                .filter(b -> CARRIED_STATUSES.contains(b.getStatus()))
                .collect(Collectors.groupingBy(Booking::getRoutePostId, Collectors.summingInt(Booking::getSeatCount)));
        return upcoming.stream()
                .map(r -> new UpcomingRouteItem(r.getId(), r.getOriginLabel(), r.getDestinationLabel(), r.getDepartureTime(),
                        r.getStatus(), r.getSeatCapacity(), r.getSeatsAvailable(), seatsBooked.getOrDefault(r.getId(), 0), r.getFarePerSeat()))
                .toList();
    }

    /** "View Passenger Manifest" after publishing (or any time after) - who's booked on this specific route.
     * Visible to the route's driver, or to any rider actively booked on it - not to anyone else, since this
     * carries every passenger's name/photo/pickup location. Only bookings that were ever paid for -
     * paid-then-cancelled and no-shows stay visible so the driver sees the full picture, but a hold
     * that expired or failed payment never was a passenger. */
    public List<BookingSummary> getManifestForRoute(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        List<Booking> bookings = bookingRepository.findByRoutePostId(routePostId);

        boolean isDriver = callerId.equals(route.getDriverId());
        boolean isActiveRider = bookings.stream()
                .anyMatch(b -> callerId.equals(b.getRiderId()) && ACTIVE_TRIP_STATUSES.contains(b.getStatus()));
        if (!isDriver && !isActiveRider) {
            throw new UnauthorizedException("Only this route's driver or a rider booked on it can view its passenger manifest");
        }

        List<Booking> manifest = bookings.stream()
                .filter(b -> b.getConfirmedAt() != null)
                .toList();
        Map<String, RatingRepository.RatingSummary> riderRatings = riderRatingsFor(manifest);
        Map<String, ProfileServiceClient.RiderSummaryItem> riders = ridersFor(manifest);
        return manifest.stream().map(b -> toSummary(b, riderRatings, riders)).toList();
    }

    /** The Receipt screen - ownership-gated same as the manifest. Passengers are the riders actually
     * carried (plus no-shows, so the driver sees who didn't turn up); seatsBooked/totalEarned count only
     * the carried ones, since a no-show or cancellation paid the driver nothing. receiptReference is
     * derived deterministically so the same trip always shows the same reference number. */
    public TripReceiptResponse getTripReceipt(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can view its receipt");
        }

        List<Booking> booked = bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> CARRIED_STATUSES.contains(b.getStatus()) || b.getStatus() == BookingStatus.NO_SHOW)
                .toList();
        List<Booking> carried = booked.stream().filter(b -> CARRIED_STATUSES.contains(b.getStatus())).toList();

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

        int seatsBooked = carried.stream().mapToInt(Booking::getSeatCount).sum();
        BigDecimal totalEarned = carried.stream().map(Booking::getFareTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
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

    private BookingSummary toSummary(Booking booking, Map<String, RatingRepository.RatingSummary> riderRatings,
                                             Map<String, ProfileServiceClient.RiderSummaryItem> riders) {
        RatingRepository.RatingSummary rating = riderRatings.get(booking.getRiderId());
        ProfileServiceClient.RiderSummaryItem rider = riders.get(booking.getRiderId());
        return new BookingSummary(booking.getId(), booking.getRiderId(),
                rider == null ? null : rider.fullName(), rider == null ? null : rider.photoUrl(),
                booking.getSeatCount(),
                booking.getFareTotal(), booking.getStatus(), booking.getPickupConfirmedAt(),
                booking.getPickupLabel(), booking.getPickupLat(), booking.getPickupLng(),
                booking.getDropoffLabel(), booking.getDropoffLat(), booking.getDropoffLng(),
                rating == null ? null : rating.getAvgStars(),
                rating == null ? null : rating.getRatingCount(),
                identityVerificationCacheRepository.existsByUserIdAndVerifiedTrue(booking.getRiderId()));
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
    public DriverTripStatsResponse getStatsForDriver(String driverId, String callerId) {
        requireSelf(driverId, callerId);
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

    /** Driver Trip History screen - one row per posted route (not per booking), newest first, optionally filtered by status bucket and searched by origin/destination. No status picked means every route regardless of status (including still-upcoming PUBLISHED/FULL ones), not just the "already happened" ONGOING/COMPLETED/CANCELLED buckets. seatsBooked/totalEarned count only riders carried or still booked, the same as the receipt - a cancelled route shows zeros because it earned nothing. */
    public PagedResponse<DriverTripHistoryItem> getHistoryForDriver(String driverId, String callerId, TripHistoryStatus statusBucket, String search, Pageable pageable) {
        requireSelf(driverId, callerId);
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
                    .filter(b -> CARRIED_STATUSES.contains(b.getStatus()))
                    .toList();
            int seatsBooked = booked.stream().mapToInt(Booking::getSeatCount).sum();
            BigDecimal totalEarned = booked.stream().map(Booking::getFareTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new DriverTripHistoryItem(route.getId(), route.getOriginLabel(), route.getDestinationLabel(),
                    route.getDepartureTime(), route.getStatus(), seatsBooked, totalEarned);
        }).toList();

        return PagedResponse.from(new PageImpl<>(items, pageable, routes.getTotalElements()));
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
    public PagedResponse<TripHistoryItem> getHistory(String riderId, String callerId, TripHistoryStatus statusBucket, String search, Pageable pageable) {
        requireSelf(riderId, callerId);
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
    public String shareTrip(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        if (!callerId.equals(booking.getRiderId())) {
            throw new UnauthorizedException("Only this booking's rider can share it");
        }
        RoutePost route = routeOf(booking);

        List<ProfileServiceClient.RiderContact> contacts = profileServiceClient.fetchEmergencyContacts(booking.getRiderId());
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

    /** Driver taps "Share trip" - the same signed link, for the whole route, texted to the driver's own contacts. */
    @Transactional
    public String shareRoute(String routePostId, String callerId) {
        RoutePost route = activeRouteOfDriver(routePostId, callerId, "share it");
        List<ProfileServiceClient.RiderContact> contacts = profileServiceClient.fetchEmergencyContacts(callerId);
        if (contacts.isEmpty()) {
            throw new BadRequestException("Add an emergency contact to your profile before sharing a trip");
        }
        String link = shareTokenService.generateLink(ShareTokenService.ROUTE_PREFIX + routePostId,
                route.getDepartureTime().plus(SHARE_LINK_VALID_HOURS, ChronoUnit.HOURS));

        kafkaTemplate.send(KafkaTopics.TRIP_SHARE_REQUESTED, TripShareRequestedEvent.builder()
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
        if (bookingId.startsWith(ShareTokenService.ROUTE_PREFIX)) {
            return sharedRouteView(bookingId.substring(ShareTokenService.ROUTE_PREFIX.length()));
        }

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
                isFullyVerified(route.getDriverId()),
                vehicle == null ? null : vehicle.make(),
                vehicle == null ? null : vehicle.model(),
                vehicle == null ? booking.getVehiclePlateSnapshot() : vehicle.plateNumber(),
                vehicle != null && vehicle.hasAC(),
                locationService.getLocation(bookingId)
        );
    }

    /** A driver's shared trip - no booking, so no booking status; the location is the driver's own. */
    private SharedTripResponse sharedRouteView(String routePostId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        ProfileServiceClient.DriverSummaryItem driver = profileServiceClient
                .fetchDriverSummaries(List.of(route.getDriverId()))
                .get(route.getDriverId());
        ProfileServiceClient.VehicleSummaryItem vehicle = route.getVehicleId() == null ? null
                : profileServiceClient.fetchVehicleSummaries(List.of(route.getVehicleId())).get(route.getVehicleId());

        return new SharedTripResponse(
                route.getOriginLabel(), route.getDestinationLabel(), route.getDepartureTime(),
                null,
                driver == null ? null : driver.legalName(),
                driver == null ? null : driver.photoUrl(),
                isFullyVerified(route.getDriverId()),
                vehicle == null ? null : vehicle.make(),
                vehicle == null ? null : vehicle.model(),
                vehicle == null ? null : vehicle.plateNumber(),
                vehicle != null && vehicle.hasAC(),
                locationService.peekRouteLocation(routePostId)
        );
    }

    /** Serves both Pay Now (status=HELD) and Booking Confirmed (status=CONFIRMED) - see BookingDetailsResponse. */
    public BookingDetailsResponse getBookingDetails(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        requireParticipant(booking, routeOf(booking), callerId);
        return buildDetails(booking, callerId.equals(booking.getRiderId()));
    }

    /** forRider decides whether the pickup code is included - the driver asking must never see it. */
    private BookingDetailsResponse buildDetails(Booking booking, boolean forRider) {
        RoutePost route = routeOf(booking);

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
                booking.getHoldExpiresAt(),
                booking.getStatus() == BookingStatus.RESERVED ? RouteService.RESERVATION_HOLD_MINUTES : SEAT_HOLD_MINUTES,
                route.getDriverId(),
                driver == null ? booking.getDriverNameSnapshot() : driver.legalName(),
                driverPhone,
                driver == null ? null : driver.photoUrl(),
                isFullyVerified(route.getDriverId()),
                vehicle == null ? null : vehicle.make(),
                vehicle == null ? null : vehicle.model(),
                vehicle == null ? booking.getVehiclePlateSnapshot() : vehicle.plateNumber(),
                vehicle != null && vehicle.hasAC(),
                booking.getStatus() == BookingStatus.COMPLETED ? computeDurationMinutes(booking.getId()) : null,
                overviewPolyline(route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng()),
                forRider && AWAITING_PICKUP_STATUSES.contains(booking.getStatus()) ? booking.getPickupCode() : null
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
    public List<BookingStatusHistoryEntry> getStatusHistory(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        requireParticipant(booking, routeOf(booking), callerId);
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

    /** A rider can hold several bookings as long as the trips don't overlap (TripScheduleService), but
     * only one unpaid checkout at a time. A retry of the same request (a flaky network resending the tap)
     * gets back the hold it already made; a hold left on another route or seat count is released, since
     * the rider has clearly moved on from it. */
    @Transactional
    public Booking reserveSeats(String routePostId, String riderId, BookSeatsRequest request) {
        bookingRepository.lockUser(riderId);
        requireNotSuspended(riderId);
        requireVerifiedRider(riderId);
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        if (riderId.equals(route.getDriverId())) {
            throw new BadRequestException("You can't book a seat on your own route");
        }
        if (userBlockService.keptApart(riderId, route.getDriverId())) {
            throw new BadRequestException("This ride isn't available to you");
        }
        if (route.getStatus() == RouteStatus.FULL) {
            throw new BadRequestException("This ride is full");
        }
        if (route.getStatus() != RouteStatus.PUBLISHED) {
            throw new BadRequestException("This ride is no longer taking bookings");
        }
        if (!route.getDepartureTime().isAfter(Instant.now())) {
            throw new BadRequestException("This ride has already departed");
        }
        if (bookingRepository.findFirstByRiderIdAndRoutePostIdAndStatus(riderId, routePostId, BookingStatus.RESERVED).isPresent()) {
            throw new BadRequestException("Your favourite driver already reserved you a seat on this route - accept or decline that reservation instead of booking a fresh one");
        }
        for (Booking held : bookingRepository.findByRiderIdAndStatus(riderId, BookingStatus.HELD)) {
            boolean sameAttempt = held.getRoutePostId().equals(routePostId)
                    && held.getSeatCount().equals(request.seatCount())
                    && held.getHoldExpiresAt().isAfter(Instant.now());
            if (sameAttempt) {
                return held;
            }
        }
        if (routeMatcher.enabled()
                && routeMatcher.metersFromRoute(route, request.pickupLat(), request.pickupLng()) > matchingProperties.getWalkMeters()) {
            throw new BadRequestException("Pick a pickup point on the driver's route");
        }
        tripScheduleService.requireNoOverlap(riderId, route);
        releaseOtherHolds(riderId, null);

        int updated = routePostRepository.holdSeats(routePostId, request.seatCount());
        if (updated == 0) {
            throw new BadRequestException("Not enough seats available");
        }
        routePostRepository.markFullIfExhausted(routePostId);
        routePostRepository.lockOnFirstBooking(routePostId, Instant.now());
        eventPublisher.publishEvent(new RouteSearchRelevantChangeEvent(routePostId));

        BigDecimal fareTotal = route.getFarePerSeat().multiply(BigDecimal.valueOf(request.seatCount()));

        Booking booking = Booking.builder()
                .routePostId(routePostId)
                .riderId(riderId)
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
                .dropoffLabel(request.dropoffLabel())
                .dropoffLat(request.dropoffLat())
                .dropoffLng(request.dropoffLng())
                .build();
        bookingRepository.save(booking);
        recordHistory(booking.getId(), BookingStatus.HELD, "Seat held pending payment");

        return booking;
    }

    /**
     * PAYMENT_SUCCEEDED can arrive after SeatHoldExpiryJob has already released the seat, and
     * Paystack resends webhooks, so this confirms only a booking that is still HELD - atomically, so
     * the expiry sweep and this can't both win. A payment for a booking already gone is refunded
     * through the normal cancellation pipeline; a repeat for one already confirmed is ignored.
     */
    @Transactional
    public void confirm(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        if (booking.getConfirmedAt() != null) {
            return;
        }
        // A route cancelled between the hold and the payment can't take the rider anywhere.
        boolean routeGone = FINISHED_ROUTE_STATUSES.contains(routeOf(booking).getStatus());
        if (routeGone) {
            moveStatus(booking, BookingStatus.CANCELLED_DRIVER, List.of(BookingStatus.HELD));
        }
        if (routeGone || !moveStatus(booking, BookingStatus.CONFIRMED, List.of(BookingStatus.HELD))) {
            log.warn("Payment succeeded for booking {} but it's no longer HELD (status={}) - refunding instead of confirming",
                    bookingId, booking.getStatus());
            kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                    .bookingId(booking.getId())
                    .routePostId(booking.getRoutePostId())
                    .riderId(booking.getRiderId())
                    .cancelledBy("SYSTEM")
                    .feeApplied(false)
                    .paid(true)
                    .cancelledAt(LocalDateTime.now())
                    .build());
            return;
        }

        booking.setConfirmedAt(Instant.now());
        booking.setPickupCode(newPickupCode(booking.getRoutePostId()));
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.CONFIRMED, "Payment succeeded");

        kafkaTemplate.send(KafkaTopics.BOOKING_CONFIRMED, BookingConfirmedEvent.builder()
                .bookingId(booking.getId())
                .routePostId(booking.getRoutePostId())
                .riderId(booking.getRiderId())
                .seatCount(booking.getSeatCount())
                .fareTotal(booking.getFareTotal())
                .pickupCode(booking.getPickupCode())
                .confirmedAt(LocalDateTime.now())
                .build());
    }

    /** A failure webhook can land after the hold already expired, or after a retry on another
     * method paid - only a still-HELD booking is released, otherwise its seat would go back twice. */
    @Transactional
    public void releaseOnPaymentFailure(String bookingId) {
        releaseUnpaid(getOrThrow(bookingId), BookingStatus.HELD, "Payment failed - seat released");
    }

    /** SeatHoldExpiryJob's per-booking step - HELD past its payment window, or RESERVED past its answer window. */
    @Transactional
    public void expireUnpaid(Booking booking) {
        releaseUnpaid(booking, booking.getStatus(), booking.getStatus() == BookingStatus.RESERVED
                ? "Reservation not answered in time - seat released"
                : "Payment not completed in time - seat released");
    }

    /** Rider backs out of the booking flow before paying - the same outcome SeatHoldExpiryJob
     * reaches on its own once holdExpiresAt passes, just immediate. No fee, no BOOKING_CANCELLED
     * event, no driver notification - the driver never saw a booking that was only HELD. */
    @Transactional
    public void releaseHold(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        if (!callerId.equals(booking.getRiderId())) {
            throw new UnauthorizedException("Only this booking's rider can release its hold");
        }
        if (!releaseUnpaid(booking, BookingStatus.HELD, "Rider backed out before paying - seat released")) {
            throw new BadRequestException("This booking is no longer held - current status is " + booking.getStatus());
        }
    }

    /** A favourite rider taking the driver's offer - converts the reservation into a normal HELD
     * booking with a fresh 4-minute payment window, exactly as if the rider had just booked it
     * themselves. pickup fields are required here because they never existed on the RESERVED row. */
    @Transactional
    public Booking acceptReservation(String bookingId, String callerId, AcceptReservationRequest request) {
        bookingRepository.lockUser(callerId);
        Booking booking = getOrThrow(bookingId);
        if (!callerId.equals(booking.getRiderId())) {
            throw new UnauthorizedException("Only this reservation's rider can accept it");
        }
        requireVerifiedRider(callerId);
        if (booking.getStatus() == BookingStatus.RESERVED) {
            tripScheduleService.requireNoOverlap(callerId, routeOf(booking));
            releaseOtherHolds(callerId, bookingId);
        }
        if (!moveStatus(booking, BookingStatus.HELD, List.of(BookingStatus.RESERVED))) {
            throw new BadRequestException("This reservation is no longer available - current status is " + booking.getStatus());
        }

        booking.setHoldExpiresAt(Instant.now().plus(SEAT_HOLD_MINUTES, ChronoUnit.MINUTES));
        booking.setPickupLabel(request.pickupLabel());
        booking.setPickupLat(request.pickupLat());
        booking.setPickupLng(request.pickupLng());
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.HELD, "Reservation accepted - seat held pending payment");

        return booking;
    }

    /** A favourite rider turning down the driver's offer - releases the seat immediately instead
     * of making everyone wait out the full 15 minutes for a "no." */
    @Transactional
    public void declineReservation(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        if (!callerId.equals(booking.getRiderId())) {
            throw new UnauthorizedException("Only this reservation's rider can decline it");
        }
        if (!releaseUnpaid(booking, BookingStatus.RESERVED, "Reservation declined - seat released")) {
            throw new BadRequestException("This reservation is no longer available - current status is " + booking.getStatus());
        }
    }

    /** Every upcoming seat of a suspended rider: unpaid ones released, paid ones cancelled with a full refund. */
    @Transactional
    public void cancelForSuspension(String riderId) {
        List<BookingStatus> upcoming = Stream.concat(Stream.of(BookingStatus.RESERVED, BookingStatus.HELD),
                RIDER_CANCELLABLE_STATUSES.stream()).toList();
        for (Booking booking : bookingRepository.findByRiderIdAndStatusIn(riderId, upcoming)) {
            if (booking.getStatus() == BookingStatus.HELD || booking.getStatus() == BookingStatus.RESERVED) {
                releaseUnpaid(booking, booking.getStatus(), "Rider suspended - seat released");
                continue;
            }
            if (!moveStatus(booking, BookingStatus.CANCELLED_RIDER, RIDER_CANCELLABLE_STATUSES)) {
                continue;
            }
            RoutePost route = routeOf(booking);
            routePostRepository.releaseSeats(booking.getRoutePostId(), booking.getSeatCount());
            routeService.reopenIfSeatsAvailable(booking.getRoutePostId());
            recordHistory(booking.getId(), BookingStatus.CANCELLED_RIDER, "Rider suspended - refunded in full");
            kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                    .bookingId(booking.getId())
                    .routePostId(booking.getRoutePostId())
                    .riderId(riderId)
                    .driverId(route.getDriverId())
                    .cancelledBy(UserSuspensionEventListener.CANCELLED_BY)
                    .feeApplied(false)
                    .paid(true)
                    .cancelledAt(LocalDateTime.now())
                    .build());
        }
    }

    /** A still-unpaid booking (HELD or RESERVED) is simply released - there is nothing to refund and
     * the driver was never told about it. A paid one goes through the fee window and the refund. */
    @Transactional
    public void cancelByRider(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        if (!callerId.equals(booking.getRiderId())) {
            throw new UnauthorizedException("Only this booking's rider can cancel it");
        }
        if (booking.getStatus() == BookingStatus.HELD || booking.getStatus() == BookingStatus.RESERVED) {
            if (releaseUnpaid(booking, booking.getStatus(), "Cancelled by rider before paying - seat released")) {
                return;
            }
        }
        RoutePost route = routeOf(booking);

        if (!moveStatus(booking, BookingStatus.CANCELLED_RIDER, RIDER_CANCELLABLE_STATUSES)) {
            throw new BadRequestException("This booking can no longer be cancelled - current status is " + booking.getStatus());
        }
        boolean feeApplies = cancellationPolicy.isFeeApplicable(route.getDepartureTime(), Instant.now());
        BigDecimal fee = cancellationPolicy.applyFee(booking.getFareTotal(), feeApplies);
        routePostRepository.releaseSeats(booking.getRoutePostId(), booking.getSeatCount());
        routeService.reopenIfSeatsAvailable(booking.getRoutePostId());
        recordHistory(bookingId, BookingStatus.CANCELLED_RIDER, feeApplies ? "Cancelled inside free window" : "Cancelled free of charge");

        kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                .bookingId(booking.getId())
                .routePostId(booking.getRoutePostId())
                .riderId(booking.getRiderId())
                .driverId(route.getDriverId())
                .cancelledBy("RIDER")
                .feeApplied(feeApplies)
                .paid(true)
                .feeAmount(fee)
                .cancelledAt(LocalDateTime.now())
                .build());
    }

    /** Driver taps "I'm on my way" - Track Live Trip's "Driver en route to pickup" phase. A repeat
     * tap is a no-op, not a second push to the rider. */
    @Transactional
    public void markEnRouteToPickup(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        requireDriverOfLiveRoute(booking, callerId, "mark it en route");
        if (booking.getStatus() == BookingStatus.EN_ROUTE_TO_PICKUP) {
            return;
        }
        requireMove(booking, BookingStatus.EN_ROUTE_TO_PICKUP, List.of(BookingStatus.CONFIRMED));
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
        requireDriverOfLiveRoute(booking, callerId, "mark it arrived at pickup");
        if (booking.getStatus() == BookingStatus.AT_PICKUP) {
            return;
        }
        requireMove(booking, BookingStatus.AT_PICKUP, List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP));
        recordHistory(bookingId, BookingStatus.AT_PICKUP, "Driver arrived at pickup point");

        kafkaTemplate.send(KafkaTopics.DRIVER_ARRIVED, DriverArrivedEvent.builder()
                .bookingId(bookingId)
                .riderId(booking.getRiderId())
                .driverName(booking.getDriverNameSnapshot())
                .occurredAt(LocalDateTime.now())
                .build());
    }

    /** Rider taps "I've boarded", or the driver taps "Mark Picked Up" on the Passenger Manifest - either
     * way advances status to IN_PROGRESS, which is what the trip is settled on. The driver has to enter
     * the rider's pickup code, so a pickup is only ever recorded once the two have actually met; the
     * rider confirming their own boarding needs no code. Doesn't roll back on a rejected code, so the
     * spent attempt sticks. */
    @Transactional(noRollbackFor = BadRequestException.class)
    public void markPickedUp(String bookingId, String callerId, String pickupCode) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routeOf(booking);
        boolean byRider = callerId.equals(booking.getRiderId());
        if (!byRider && !callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this booking's rider or the route's driver can mark it picked up");
        }
        requireLive(route);
        if (booking.getStatus() == BookingStatus.IN_PROGRESS) {
            return;
        }
        if (!AWAITING_PICKUP_STATUSES.contains(booking.getStatus())) {
            throw new BadRequestException("This booking can't be marked picked up - current status is " + booking.getStatus());
        }
        if (!byRider) {
            verifyPickupCode(booking, pickupCode);
        }
        requireMove(booking, BookingStatus.IN_PROGRESS, AWAITING_PICKUP_STATUSES);
        booking.setPickupConfirmedAt(Instant.now());
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.IN_PROGRESS, "Rider picked up - trip in progress");
    }

    @Transactional
    public void markNoShow(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        requireDriverOfLiveRoute(booking, callerId, "mark it as a no-show");
        if (booking.getStatus() == BookingStatus.NO_SHOW) {
            return;
        }
        requireMove(booking, BookingStatus.NO_SHOW, AWAITING_PICKUP_STATUSES);
        // The driver keeps the fare on a no-show, so where they were when they claimed it is the
        // only evidence ops has when the rider says the driver never turned up.
        recordHistory(bookingId, BookingStatus.NO_SHOW,
                "Rider did not show up within the grace period" + driverLocationNote(booking.getRoutePostId()));
    }

    /** Best-effort - a driver with location sharing off still gets to mark a no-show, ops just has less to go on. */
    private String driverLocationNote(String routePostId) {
        try {
            LocationResponse location = locationService.peekRouteLocation(routePostId);
            if (location == null || !location.available()) {
                return " (driver location unavailable)";
            }
            return " (driver at " + location.lat() + "," + location.lng() + " at " + location.recordedAt() + ")";
        } catch (Exception e) {
            log.warn("Could not read driver location for route {} while marking a no-show: {}", routePostId, e.getMessage());
            return " (driver location unavailable)";
        }
    }

    /** Undoes a manifest mark (Picked Up or No Show) made in error, before the trip is completed - powers the manifest's "No Show x" undo chip. */
    @Transactional
    public void undoManifestMark(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        requireDriverOfLiveRoute(booking, callerId, "reset a manifest mark");
        if (!moveStatus(booking, BookingStatus.CONFIRMED, List.of(BookingStatus.IN_PROGRESS, BookingStatus.NO_SHOW))) {
            throw new BadRequestException("This booking hasn't been marked yet");
        }
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
        requireLive(route);
        for (Booking b : bookingRepository.findByRoutePostId(routePostId)) {
            if (moveStatus(b, BookingStatus.CONFIRMED, List.of(BookingStatus.IN_PROGRESS, BookingStatus.NO_SHOW))) {
                b.setPickupConfirmedAt(null);
                bookingRepository.save(b);
                recordHistory(b.getId(), BookingStatus.CONFIRMED, "Manifest mark reset by driver");
            }
        }
    }

    @Transactional
    public void completeTrip(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routeOf(booking);
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can complete it");
        }
        if (route.getStatus() != RouteStatus.IN_PROGRESS) {
            throw new BadRequestException("This trip hasn't been started yet");
        }
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            return;
        }
        if (!completeBookingInternal(booking, route)) {
            throw new BadRequestException("Only a picked-up passenger's trip can be completed - current status is " + booking.getStatus());
        }
    }

    /** Statuses that still need a Picked Up/No Show decision before a route-level Complete Trip is allowed. */
    private static final List<BookingStatus> UNRESOLVED_MANIFEST_STATUSES = AWAITING_PICKUP_STATUSES;

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
            completeBookingInternal(booking, route);
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

    /** TRIP_COMPLETED is what pays the driver, so it goes out only from the call that actually moved
     * the booking - a double-tapped Complete can't publish it twice. */
    private boolean completeBookingInternal(Booking booking, RoutePost route) {
        if (!moveStatus(booking, BookingStatus.COMPLETED, List.of(BookingStatus.IN_PROGRESS))) {
            return false;
        }
        recordHistory(booking.getId(), BookingStatus.COMPLETED, "Trip completed");

        kafkaTemplate.send(KafkaTopics.TRIP_COMPLETED, TripCompletedEvent.builder()
                .bookingId(booking.getId())
                .routePostId(booking.getRoutePostId())
                .riderId(booking.getRiderId())
                .driverId(route.getDriverId())
                .fareTotal(booking.getFareTotal())
                .completedAt(LocalDateTime.now())
                .build());
        return true;
    }

    /** Rater, ratee and direction all come from the booking, not the request - the caller is either
     * its rider rating the driver or its driver rating the rider, once per side, after the trip. */
    @Transactional
    public Rating rate(String bookingId, String callerId, RateRequest request) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routeOf(booking);
        RatingDirection direction;
        String rateeId;
        if (callerId.equals(booking.getRiderId())) {
            direction = RatingDirection.RIDER_TO_DRIVER;
            rateeId = route.getDriverId();
        } else if (callerId.equals(route.getDriverId())) {
            direction = RatingDirection.DRIVER_TO_RIDER;
            rateeId = booking.getRiderId();
        } else {
            throw new UnauthorizedException("Only this booking's rider or driver can rate it");
        }
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new BadRequestException("A trip can only be rated once it's completed");
        }
        if (ratingRepository.existsByBookingIdAndDirection(bookingId, direction)) {
            throw new BadRequestException("You've already rated this trip");
        }
        Rating rating = Rating.builder()
                .bookingId(bookingId)
                .raterId(callerId)
                .rateeId(rateeId)
                .direction(direction)
                .stars(request.stars())
                .comment(request.comment())
                .build();
        return ratingRepository.save(rating);
    }

    private void verifyPickupCode(Booking booking, String pickupCode) {
        if (pickupCode == null || !pickupCode.matches("\\d{4}")) {
            throw new BadRequestException("Enter the rider's 4-digit pickup code");
        }
        if (booking.getPickupCode() == null) {
            throw new BadRequestException("This booking has no pickup code - ask the rider to tap \"I've boarded\" in their app");
        }
        if (bookingRepository.spendPickupCodeAttempt(booking.getId(), MAX_PICKUP_CODE_ATTEMPTS) == 0) {
            throw new BadRequestException("Too many wrong pickup codes - ask the rider to tap \"I've boarded\" in their app");
        }
        boolean matches = MessageDigest.isEqual(
                booking.getPickupCode().getBytes(StandardCharsets.UTF_8), pickupCode.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new BadRequestException("That pickup code doesn't match this rider's booking");
        }
        bookingRepository.resetPickupCodeAttempts(booking.getId());
        // The entity's own copy is still the pre-attempt count - the save that follows would write it back.
        booking.setPickupCodeAttempts(0);
    }

    /** Distinct among the route's riders still waiting for pickup, so a driver reading codes off
     * several riders at one stop can't board one on another's code. */
    private String newPickupCode(String routePostId) {
        String code;
        int tries = 0;
        do {
            code = String.format("%04d", RANDOM.nextInt(10_000));
        } while (++tries < 20 && bookingRepository.existsByRoutePostIdAndPickupCodeAndStatusIn(routePostId, code, AWAITING_PICKUP_STATUSES));
        return code;
    }

    /** One unpaid checkout per rider - starting another releases whatever they left on the Pay Now screen. */
    private void releaseOtherHolds(String riderId, String keepBookingId) {
        for (Booking held : bookingRepository.findByRiderIdAndStatus(riderId, BookingStatus.HELD)) {
            if (!held.getId().equals(keepBookingId)) {
                releaseUnpaid(held, BookingStatus.HELD, "Replaced by a newer booking attempt - seat released");
            }
        }
    }

    /** Ends an unpaid booking and returns its seat - only if this call is the one that moved it, so
     * the expiry sweep, a failure webhook and the rider backing out can't each release the same seat. */
    private void requireVerifiedRider(String riderId) {
        if (identityVerificationPolicy.blocksUnverified() && !identityVerificationCacheRepository.existsByUserIdAndVerifiedTrue(riderId)) {
            throw new BadRequestException("Verify your identity to book a ride");
        }
    }

    private void requireNotSuspended(String userId) {
        if (suspendedUserRepository.existsById(userId)) {
            throw new BadRequestException("This account is suspended. Contact CoRyd support.");
        }
    }

    private boolean releaseUnpaid(Booking booking, BookingStatus from, String note) {
        if (!moveStatus(booking, BookingStatus.CANCELLED_RIDER, List.of(from))) {
            return false;
        }
        routePostRepository.releaseSeats(booking.getRoutePostId(), booking.getSeatCount());
        routeService.reopenIfSeatsAvailable(booking.getRoutePostId());
        recordHistory(booking.getId(), BookingStatus.CANCELLED_RIDER, note);
        return true;
    }

    /** Compare-and-set, mirrored onto the loaded entity so a later save() doesn't write the old status back. */
    private boolean moveStatus(Booking booking, BookingStatus to, List<BookingStatus> from) {
        if (bookingRepository.transitionStatus(booking.getId(), from, to) == 0) {
            return false;
        }
        booking.setStatus(to);
        return true;
    }

    private void requireMove(Booking booking, BookingStatus to, List<BookingStatus> from) {
        if (!moveStatus(booking, to, from)) {
            throw new BadRequestException("This booking can't move to " + to + " from " + booking.getStatus());
        }
    }

    private RoutePost requireDriverOfLiveRoute(Booking booking, String callerId, String action) {
        RoutePost route = routeOf(booking);
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can " + action);
        }
        requireLive(route);
        return route;
    }

    private static void requireLive(RoutePost route) {
        if (FINISHED_ROUTE_STATUSES.contains(route.getStatus())) {
            throw new BadRequestException("This trip is already " + route.getStatus().name().toLowerCase() + " - it can no longer be changed");
        }
    }

    private static void requireParticipant(Booking booking, RoutePost route, String callerId) {
        if (!callerId.equals(booking.getRiderId()) && !callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this booking's rider or driver can access it");
        }
    }

    private static void requireSelf(String userId, String callerId) {
        if (!userId.equals(callerId)) {
            throw new UnauthorizedException("You can only access your own trips");
        }
    }

    private RoutePost routeOf(Booking booking) {
        return routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
    }

    /** What payment-service checks a payment against - never the client's own figures. */
    public BookingPaymentView getPaymentView(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        return new BookingPaymentView(booking.getId(), booking.getRiderId(), routeOf(booking).getDriverId(),
                booking.getStatus(), booking.getFareTotal(), booking.getHoldExpiresAt());
    }

    /** Route text for payment-service's payment histories - one call per page, unknown ids simply left out. */
    @Transactional(readOnly = true)
    public List<BookingRouteSummary> getRouteSummaries(List<String> bookingIds) {
        List<Booking> bookings = bookingRepository.findAllById(bookingIds);
        Map<String, RoutePost> routes = routePostRepository.findAllById(bookings.stream().map(Booking::getRoutePostId).distinct().toList())
                .stream().collect(Collectors.toMap(RoutePost::getId, r -> r));
        return bookings.stream()
                .filter(b -> routes.containsKey(b.getRoutePostId()))
                .map(b -> {
                    RoutePost route = routes.get(b.getRoutePostId());
                    return new BookingRouteSummary(b.getId(), route.getId(), route.getOriginLabel(), route.getDestinationLabel(),
                            route.getDepartureTime());
                })
                .toList();
    }

    @Transactional
    public SosIncident triggerSos(String bookingId, String callerId, Double latitude, Double longitude) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routeOf(booking);
        requireParticipant(booking, route, callerId);
        return raiseSos(route, booking, callerId, identityServiceClient.fetchPhone(booking.getRiderId()),
                booking.getDriverNameSnapshot(), booking.getVehiclePlateSnapshot(), booking.getRouteSummarySnapshot(), latitude, longitude);
    }

    /** The driver's own SOS on their trip - works before anyone has booked, and with several riders on board. */
    @Transactional
    public SosIncident triggerRouteSos(String routePostId, String callerId, Double latitude, Double longitude) {
        RoutePost route = activeRouteOfDriver(routePostId, callerId, "raise an SOS on it");
        String riderPhones = bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> TripScheduleService.SEATED_STATUSES.contains(b.getStatus()))
                .map(b -> identityServiceClient.fetchPhone(b.getRiderId()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(", "));
        return raiseSos(route, null, callerId, riderPhones.isEmpty() ? null : riderPhones,
                profileServiceClient.fetchDriverName(route.getDriverId()),
                route.getVehicleId() == null ? null : profileServiceClient.fetchVehiclePlate(route.getVehicleId()),
                route.getOriginLabel() + " → " + route.getDestinationLabel(), latitude, longitude);
    }

    private RoutePost activeRouteOfDriver(String routePostId, String callerId, String action) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can " + action);
        }
        if (!SOS_ROUTE_STATUSES.contains(route.getStatus())) {
            throw new BadRequestException("This trip has already ended");
        }
        return route;
    }

    private SosIncident raiseSos(RoutePost route, Booking booking, String callerId, String riderPhone, String driverName,
                                 String vehiclePlate, String routeSummary, Double latitude, Double longitude) {
        String bookingId = booking == null ? null : booking.getId();
        String role = callerId.equals(route.getDriverId()) ? "DRIVER" : "RIDER";
        // opsNotifiedAt is when the alert left for ops (SOS_TRIGGERED below) - delivery per SMS and email
        // is in notification-service's notification_log.
        SosIncident incident = sosIncidentRepository.save(SosIncident.builder()
                .bookingId(bookingId)
                .routePostId(route.getId())
                .triggeredBy(callerId)
                .triggeredByRole(role)
                .latitude(latitude)
                .longitude(longitude)
                .opsNotifiedAt(Instant.now())
                .build());

        // Every emergency contact of whoever pressed SOS - the rider's people for a rider, the driver's
        // for a driver. Contacts belong to the person, whichever role they're in.
        List<ProfileServiceClient.RiderContact> contacts = profileServiceClient.fetchEmergencyContacts(callerId);

        // Fan-out only, never the primary path - the incident is already saved above.
        kafkaTemplate.send(KafkaTopics.SOS_TRIGGERED, SosTriggeredEvent.builder()
                .incidentId(incident.getId())
                .bookingId(bookingId)
                .routePostId(route.getId())
                .triggeredBy(callerId)
                .triggeredByRole(role)
                .riderPhone(riderPhone)
                .driverPhone(identityServiceClient.fetchPhone(route.getDriverId()))
                .driverName(driverName)
                .vehiclePlate(vehiclePlate)
                .routeSummary(routeSummary)
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
    public IssueReport reportIssue(IssueReportRequest request, String callerId) {
        if (request.bookingId() != null) {
            Booking booking = getOrThrow(request.bookingId());
            requireParticipant(booking, routeOf(booking), callerId);
        }
        String referenceNumber = "ISS-" + String.format("%08d", RANDOM.nextInt(100_000_000));
        IssueReport issue = issueReportRepository.save(IssueReport.builder()
                .bookingId(request.bookingId())
                .reportedBy(callerId)
                .category(request.category())
                .description(request.description())
                .referenceNumber(referenceNumber)
                .status("OPEN")
                .build());

        kafkaTemplate.send(KafkaTopics.ISSUE_REPORTED, IssueReportedEvent.builder()
                .issueId(issue.getId())
                .bookingId(request.bookingId())
                .reportedBy(callerId)
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
