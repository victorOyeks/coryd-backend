package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RouteTimeChangedEvent;
import org.coryd.trip.config.TimeChangeProperties;
import org.coryd.trip.dto.ChangeTripTimeRequest;
import org.coryd.trip.dto.OpsTimeChangeDriverItem;
import org.coryd.trip.dto.RouteTimeChangeResponse;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.RouteTimeChange;
import org.coryd.trip.event.RouteSearchRelevantChangeEvent;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteTimeChangeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A driver running late moves their trip instead of cancelling it. With nobody booked it's a plain edit,
 * earlier or later. Once riders have booked it can only move later, within a limit and a few times, and
 * every paid rider is told - one whose trip has moved far enough from what they booked can cancel free.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteTimeChangeService {

    private static final List<RouteStatus> NOT_STARTED = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL);
    /** Everyone holding a place on the trip, paid or not - they all booked a time. */
    private static final List<BookingStatus> HOLDING_A_PLACE = List.of(BookingStatus.HELD, BookingStatus.RESERVED,
            BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP);
    private static final List<BookingStatus> PAID = List.of(BookingStatus.CONFIRMED,
            BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP);

    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final RouteTimeChangeRepository routeTimeChangeRepository;
    private final TripScheduleService tripScheduleService;
    private final NotifyMeService notifyMeService;
    private final TimeChangeProperties properties;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${trip.max-days-ahead:3}")
    private int maxDaysAhead;

    @Transactional
    public RouteTimeChangeResponse change(String routePostId, String callerId, ChangeTripTimeRequest request) {
        if ((request.departureTime() == null) == (request.minutes() == null)) {
            throw new BadRequestException("Send either the new departureTime or minutes to add, not both");
        }
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can change its time");
        }
        bookingRepository.lockUser(route.getDriverId());
        if (!NOT_STARTED.contains(route.getStatus())) {
            throw new BadRequestException("Only a trip that hasn't started can change its time - this one is " + route.getStatus());
        }

        Instant now = Instant.now();
        Instant previous = route.getDepartureTime();
        Instant next = request.departureTime() != null ? request.departureTime() : previous.plus(Duration.ofMinutes(request.minutes()));
        if (!next.isAfter(now)) {
            throw new BadRequestException("The new departure time has already passed");
        }
        if (next.equals(previous)) {
            throw new BadRequestException("That's already the departure time");
        }
        if (next.isAfter(now.plus(Duration.ofDays(maxDaysAhead)))) {
            throw new BadRequestException("Routes can only be posted up to " + maxDaysAhead + " days ahead");
        }

        List<Booking> holding = bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> HOLDING_A_PLACE.contains(b.getStatus()))
                .toList();
        long later = Duration.between(previous, next).toMinutes();
        if (!holding.isEmpty()) {
            requireAllowedWithRiders(route, previous, next, later, holding);
        }

        route.setDepartureTime(next);
        tripScheduleService.requireNoOverlap(route.getDriverId(), route);
        if (route.getOriginalDepartureTime() == null) {
            route.setOriginalDepartureTime(previous);
        }
        if (!holding.isEmpty()) {
            route.setTimeChangeCount(route.getTimeChangeCount() + 1);
        }
        // Every reminder belongs to the old time - the new one gets its own.
        route.setReminderSentAt(null);
        route.setDriverReminderSentAt(null);
        route.setStartNudgeSentAt(null);
        routePostRepository.save(route);

        holding.forEach(b -> b.setDepartureShiftMinutes(b.getDepartureShiftMinutes() + (int) Math.max(0, later)));
        bookingRepository.saveAll(holding);

        List<Booking> paid = holding.stream().filter(b -> PAID.contains(b.getStatus())).toList();
        boolean lastMinute = !paid.isEmpty()
                && Duration.between(now, previous).toMinutes() <= properties.getLastMinuteMinutes();
        routeTimeChangeRepository.save(RouteTimeChange.builder()
                .routePostId(routePostId)
                .driverId(route.getDriverId())
                .previousDepartureTime(previous)
                .newDepartureTime(next)
                .ridersBooked(paid.size())
                .lastMinute(lastMinute)
                .build());

        if (!paid.isEmpty()) {
            kafkaTemplate.send(KafkaTopics.ROUTE_TIME_CHANGED, RouteTimeChangedEvent.builder()
                    .routePostId(routePostId)
                    .driverId(route.getDriverId())
                    .originLabel(route.getOriginLabel())
                    .destinationLabel(route.getDestinationLabel())
                    .previousDepartureTime(previous)
                    .departureTime(next)
                    .riders(paid.stream().map(b -> RouteTimeChangedEvent.RiderBooking.builder()
                            .riderId(b.getRiderId())
                            .bookingId(b.getId())
                            .freeCancellation(freeCancellation(b))
                            .build()).toList())
                    .build());
        }
        // Riders searching or waiting for the new time now see it.
        eventPublisher.publishEvent(new RouteSearchRelevantChangeEvent(routePostId));
        notifyMeService.notifyMatching(route);

        log.info("Route {} moved from {} to {} by its driver ({} paid rider(s) told)", routePostId, previous, next, paid.size());
        return new RouteTimeChangeResponse(routePostId, previous, next, route.getOriginalDepartureTime(),
                route.getTimeChangeCount(), Math.max(0, properties.getMaxCount() - route.getTimeChangeCount()), paid.size());
    }

    /** Has this booking's trip moved far enough from what they booked that cancelling costs nothing. */
    public boolean freeCancellation(Booking booking) {
        return booking.getDepartureShiftMinutes() > properties.getFreeCancelMinutes();
    }

    public List<OpsTimeChangeDriverItem> frequentLastMinuteChangers(int days, int minLastMinute) {
        Instant from = Instant.now().minus(Duration.ofDays(Math.max(1, days)));
        return routeTimeChangeRepository.frequentLastMinuteChangers(from, Math.max(1, minLastMinute)).stream()
                .map(d -> new OpsTimeChangeDriverItem(d.getDriverId(), d.getLastMinuteChanges(), d.getTotalChanges(), d.getLastChangedAt()))
                .toList();
    }

    private void requireAllowedWithRiders(RoutePost route, Instant previous, Instant next, long later, List<Booking> holding) {
        if (!next.isAfter(previous)) {
            throw new BadRequestException("Riders have booked this trip, so it can only move later. To leave earlier, cancel it and post again.");
        }
        if (route.getTimeChangeCount() >= properties.getMaxCount()) {
            throw new BadRequestException("This trip's time has already been changed " + properties.getMaxCount()
                    + " times. To change it again, cancel it and post again.");
        }
        int furthest = holding.stream().mapToInt(Booking::getDepartureShiftMinutes).max().orElse(0);
        if (furthest + later > properties.getMaxMinutes()) {
            int left = Math.max(0, properties.getMaxMinutes() - furthest);
            throw new BadRequestException("Riders have booked this trip, so it can move at most " + properties.getMaxMinutes()
                    + " minutes later than they booked" + (left == 0 ? " - it already has." : " - up to " + left + " more minutes."));
        }
    }
}
