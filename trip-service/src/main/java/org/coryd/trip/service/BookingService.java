package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.BookingConfirmedEvent;
import org.coryd.common.kafka.events.IssueReportedEvent;
import org.coryd.common.kafka.events.SosTriggeredEvent;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.CancellationPolicy;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.IssueReportRequest;
import org.coryd.trip.dto.RateRequest;
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

@Service
@RequiredArgsConstructor
public class BookingService {

    private static final int SEAT_HOLD_MINUTES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingRepository bookingRepository;
    private final BookingStatusHistoryRepository historyRepository;
    private final RoutePostRepository routePostRepository;
    private final RatingRepository ratingRepository;
    private final SosIncidentRepository sosIncidentRepository;
    private final IssueReportRepository issueReportRepository;
    private final CancellationPolicy cancellationPolicy;
    private final ProfileServiceClient profileServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

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

    @Transactional
    public void markPickedUp(String bookingId) {
        Booking booking = getOrThrow(bookingId);
        booking.setPickupConfirmedAt(Instant.now());
        bookingRepository.save(booking);
        recordHistory(bookingId, booking.getStatus(), "Rider picked up");
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
        SosIncident incident = sosIncidentRepository.save(SosIncident.builder()
                .bookingId(bookingId)
                .triggeredBy(triggeredBy)
                .latitude(latitude)
                .longitude(longitude)
                .opsNotifiedAt(Instant.now())
                .build());

        // Fan-out only, never the primary path - the incident is already saved above.
        kafkaTemplate.send(KafkaTopics.SOS_TRIGGERED, SosTriggeredEvent.builder()
                .incidentId(incident.getId().toString())
                .bookingId(bookingId.toString())
                .triggeredBy(triggeredBy.toString())
                .latitude(latitude)
                .longitude(longitude)
                .triggeredAt(LocalDateTime.now())
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
