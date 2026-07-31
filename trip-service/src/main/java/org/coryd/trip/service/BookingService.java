package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.BookingConfirmedEvent;
import org.coryd.common.kafka.events.IssueReportedEvent;
import org.coryd.common.kafka.events.SosTriggeredEvent;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.common.kafka.events.TripShareRequestedEvent;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.CancellationPolicy;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.BookingDetailsResponse;
import org.coryd.trip.dto.IssueReportRequest;
import org.coryd.trip.dto.RateRequest;
import org.coryd.trip.dto.SharedTripResponse;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final int SHARE_LINK_VALID_HOURS = 4;

    /** Last 5 bookings, newest first - powers the Home screen's "Recent Routes" list. */
    public List<Booking> getRecentRoutesForRider(String riderId) {
        return bookingRepository.findTop5ByRiderIdOrderByCreatedAtDesc(riderId);
    }

    private static final List<BookingStatus> ACTIVE_TRIP_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);

    /** Powers the Home screen's "Go back to your current trip" banner - empty if the rider has no trip in flight. */
    public Optional<BookingDetailsResponse> getActiveBookingForRider(String riderId) {
        return bookingRepository.findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(riderId, ACTIVE_TRIP_STATUSES)
                .map(booking -> getBookingDetails(booking.getId()));
    }

    /** Rider taps "Share trip details" - generates the signed link and hands off to notification-service (via Kafka) to actually SMS the emergency contact, not a direct HTTP call. */
    @Transactional
    public String shareTrip(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        ProfileServiceClient.RiderContact contact = profileServiceClient.fetchRiderContact(booking.getRiderId());
        if (contact == null || contact.emergencyContactPhone() == null || contact.emergencyContactPhone().isBlank()) {
            throw new BadRequestException("Add an emergency contact to your profile before sharing a trip");
        }

        Instant expiresAt = route.getDepartureTime().plus(SHARE_LINK_VALID_HOURS, ChronoUnit.HOURS);
        String link = shareTokenService.generateLink(bookingId, expiresAt);

        kafkaTemplate.send(KafkaTopics.TRIP_SHARE_REQUESTED, TripShareRequestedEvent.builder()
                .bookingId(bookingId)
                .riderId(booking.getRiderId())
                .shareLink(link)
                .contactName(contact.emergencyContactName())
                .contactPhone(contact.emergencyContactPhone())
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
                vehicle != null && vehicle.hasAC()
        );
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
                .bookingId(booking.getId().toString())
                .routePostId(booking.getRoutePostId().toString())
                .riderId(booking.getRiderId().toString())
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
    public void cancelByRider(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        boolean feeApplies = cancellationPolicy.isFeeApplicable(route.getDepartureTime(), Instant.now());
        routePostRepository.releaseSeats(booking.getRoutePostId(), booking.getSeatCount());

        booking.setStatus(BookingStatus.CANCELLED_RIDER);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.CANCELLED_RIDER, feeApplies ? "Cancelled inside free window" : "Cancelled free of charge");

        kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                .bookingId(booking.getId().toString())
                .routePostId(booking.getRoutePostId().toString())
                .riderId(booking.getRiderId().toString())
                .cancelledBy("RIDER")
                .feeApplied(feeApplies)
                .cancelledAt(LocalDateTime.now())
                .build());
    }

    /** Driver taps "I'm on my way" - Track Live Trip's "Driver en route to pickup" phase. */
    @Transactional
    public void markEnRouteToPickup(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        booking.setStatus(BookingStatus.EN_ROUTE_TO_PICKUP);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.EN_ROUTE_TO_PICKUP, "Driver en route to pickup");
    }

    /** Driver taps "I've arrived" - Track Live Trip's "Driver at pickup point" phase. */
    @Transactional
    public void markArrivedAtPickup(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        booking.setStatus(BookingStatus.AT_PICKUP);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.AT_PICKUP, "Driver arrived at pickup point");
    }

    /** Rider taps "I've boarded" - advances status to IN_PROGRESS, not just a timestamp, so it drives the Track Live Trip phase timeline too. */
    @Transactional
    public void markPickedUp(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        booking.setPickupConfirmedAt(Instant.now());
        booking.setStatus(BookingStatus.IN_PROGRESS);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.IN_PROGRESS, "Rider picked up - trip in progress");
    }

    @Transactional
    public void markNoShow(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        booking.setStatus(BookingStatus.NO_SHOW);
        bookingRepository.save(booking);
        recordHistory(bookingId, BookingStatus.NO_SHOW, "Rider did not show up within the grace period");
    }

    @Transactional
    public void completeTrip(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

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

        // The rider's own emergency contact regardless of who triggered SOS - it's a rider-safety
        // feature. Looked up here (not in notification-service) since this service already has
        // fetchRiderContact for Share Trip - no new cross-service dependency needed there.
        ProfileServiceClient.RiderContact contact = profileServiceClient.fetchRiderContact(booking.getRiderId());

        // Fan-out only, never the primary path - the incident is already saved above.
        kafkaTemplate.send(KafkaTopics.SOS_TRIGGERED, SosTriggeredEvent.builder()
                .incidentId(incident.getId().toString())
                .bookingId(bookingId.toString())
                .triggeredBy(triggeredBy.toString())
                .latitude(latitude)
                .longitude(longitude)
                .triggeredAt(LocalDateTime.now())
                .emergencyContactName(contact == null ? null : contact.emergencyContactName())
                .emergencyContactPhone(contact == null ? null : contact.emergencyContactPhone())
                .build());

        return incident;
    }

    @Transactional
    public IssueReport reportIssue(String bookingId, IssueReportRequest request) {
        String referenceNumber = "ISS-" + String.format("%08d", RANDOM.nextInt(100_000_000));
        IssueReport issue = issueReportRepository.save(IssueReport.builder()
                .bookingId(bookingId)
                .reportedBy(request.reportedBy())
                .category(request.category())
                .description(request.description())
                .referenceNumber(referenceNumber)
                .status("OPEN")
                .build());

        kafkaTemplate.send(KafkaTopics.ISSUE_REPORTED, IssueReportedEvent.builder()
                .issueId(issue.getId().toString())
                .bookingId(bookingId.toString())
                .reportedBy(request.reportedBy().toString())
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
