package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
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
import org.coryd.trip.dto.SharedTripResponse;
import org.coryd.trip.dto.TripHistoryItem;
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
                driverId, List.of(RouteStatus.PUBLISHED, RouteStatus.FULL), startOfToday());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        RoutePost route = candidates.get(0);
        List<Booking> activeBookings = bookingRepository.findByRoutePostId(route.getId()).stream()
                .filter(b -> ACTIVE_TRIP_STATUSES.contains(b.getStatus()))
                .toList();

        List<BookingSummary> bookingSummaries = activeBookings.stream()
                .map(BookingService::toSummary)
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
        return bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> b.getStatus() != BookingStatus.HELD)
                .map(BookingService::toSummary)
                .toList();
    }

    private static BookingSummary toSummary(Booking booking) {
        return new BookingSummary(booking.getId(), booking.getRiderId(), booking.getSeatCount(),
                booking.getFareTotal(), booking.getStatus(), booking.getPickupConfirmedAt());
    }

    /** Dashboard's "Trips this week" tile - completed bookings on routes that departed Monday through now. */
    public DriverTripStatsResponse getStatsForDriver(String driverId) {
        List<String> routeIdsThisWeek = routePostRepository
                .findByDriverIdAndDepartureTimeBetween(driverId, startOfWeek(), Instant.now())
                .stream().map(RoutePost::getId).toList();
        if (routeIdsThisWeek.isEmpty()) {
            return new DriverTripStatsResponse(0);
        }
        long tripsThisWeek = bookingRepository.countByRoutePostIdInAndStatus(routeIdsThisWeek, BookingStatus.COMPLETED);
        return new DriverTripStatsResponse((int) tripsThisWeek);
    }

    /** Driver Trip History screen - one row per posted route (not per booking), newest first. Net earnings aren't included here - payment-service data, composed client-side from completedBookingIds. */
    public PagedResponse<DriverTripHistoryItem> getHistoryForDriver(String driverId, Pageable pageable) {
        Page<RoutePost> routes = routePostRepository.findByDriverIdAndStatusNotAndDepartureTimeBeforeOrderByDepartureTimeDesc(
                driverId, RouteStatus.CANCELLED, Instant.now(), pageable);

        List<String> routePostIds = routes.getContent().stream().map(RoutePost::getId).toList();
        Map<String, List<Booking>> bookingsByRoute = routePostIds.isEmpty() ? Map.of()
                : bookingRepository.findByRoutePostIdIn(routePostIds).stream()
                        .collect(Collectors.groupingBy(Booking::getRoutePostId));

        List<DriverTripHistoryItem> items = routes.getContent().stream().map(route -> {
            List<Booking> completed = bookingsByRoute.getOrDefault(route.getId(), List.of()).stream()
                    .filter(b -> b.getStatus() == BookingStatus.COMPLETED)
                    .toList();
            int passengerCount = completed.stream().mapToInt(Booking::getSeatCount).sum();
            List<String> completedBookingIds = completed.stream().map(Booking::getId).toList();
            return new DriverTripHistoryItem(route.getId(), route.getOriginLabel(), route.getDestinationLabel(),
                    route.getDepartureTime(), passengerCount, completedBookingIds);
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
                booking.getId(), booking.getStatus(),
                route.getOriginLabel(), route.getDestinationLabel(), route.getDepartureTime(),
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
                routeViaSummary(route),
                booking.getStatus() == BookingStatus.COMPLETED ? computeDurationMinutes(bookingId) : null
        );
    }

    /** Track Live Trip's status timeline - kept off BookingDetailsResponse since Pay Now/Booking Confirmed don't render it. */
    public List<BookingStatusHistoryEntry> getStatusHistory(String bookingId) {
        return historyRepository.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(h -> new BookingStatusHistoryEntry(h.getStatus(), h.getCreatedAt()))
                .toList();
    }

    /** Best-effort - a Directions API hiccup shouldn't take down the whole booking-details screen over one text line. */
    private String routeViaSummary(RoutePost route) {
        try {
            return mapsService.getRoutePreview(
                    route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng()
            ).viaSummary();
        } catch (Exception e) {
            log.warn("Route preview unavailable for booking details (routePostId={}): {}", route.getId(), e.getMessage());
            return null;
        }
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
                .build();
        bookingRepository.save(booking);
        recordHistory(booking.getId(), BookingStatus.HELD, "Seat held pending payment");

        return booking;
    }

    @Transactional
    public void confirm(String bookingId) {
        Booking booking = getOrThrow(bookingId);
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

    /** Rider taps "I've boarded" - advances status to IN_PROGRESS, not just a timestamp, so it drives the Track Live Trip phase timeline too. */
    @Transactional
    public void markPickedUp(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        if (!callerId.equals(booking.getRiderId())) {
            throw new UnauthorizedException("Only this booking's rider can mark it picked up");
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

    @Transactional
    public void completeTrip(String bookingId, String callerId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can complete it");
        }

        booking.setStatus(BookingStatus.COMPLETED);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.COMPLETED, "Trip completed");

        kafkaTemplate.send(KafkaTopics.TRIP_COMPLETED, TripCompletedEvent.builder()
                .bookingId(booking.getId().toString())
                .routePostId(booking.getRoutePostId().toString())
                .riderId(booking.getRiderId().toString())
                .driverId(route.getDriverId().toString())
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
