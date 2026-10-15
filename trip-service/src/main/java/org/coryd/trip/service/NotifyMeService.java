package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.NotifyMeMatchedEvent;
import org.coryd.trip.config.DemandProperties;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.dto.NotifyMeCreateRequest;
import org.coryd.trip.dto.NotifyMeResponse;
import org.coryd.trip.entity.NotifyMeRequest;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.util.GeoMath;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A rider's "Notify me when a driver is heading this way" - one ride, or repeating (e.g. weekdays
 * around 7 AM for 4 weeks). When a driver posts or reopens a ride that fits, the rider is told once
 * per ride.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyMeService {

    private final NotifyMeRequestRepository notifyMeRequestRepository;
    private final MatchingProperties matchingProperties;
    private final DemandProperties demandProperties;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final BookingRepository bookingRepository;

    /** Holding a seat (paying for it) or seated on the ride. */
    private static final List<BookingStatus> BOOKED = Stream.concat(Stream.of(BookingStatus.HELD),
            TripScheduleService.SEATED_STATUSES.stream()).toList();
    private static final List<String> BOOKED_NAMES = BOOKED.stream().map(Enum::name).toList();

    @Transactional
    public NotifyMeResponse create(String riderId, NotifyMeCreateRequest request) {
        // Several taps at once would all pass the count below before any of them saved - one at a time per rider.
        bookingRepository.lockUser(riderId);
        Instant now = Instant.now();
        if (notifyMeRequestRepository.countByRiderIdAndCancelledFalseAndFulfilledFalseAndExpiresAtAfter(riderId, now)
                >= demandProperties.getMaxActiveNotifyMe()) {
            throw new BadRequestException("You already have " + demandProperties.getMaxActiveNotifyMe()
                    + " routes you're waiting on - remove one to add another");
        }
        NotifyMeRequest.NotifyMeRequestBuilder<?, ?> builder = NotifyMeRequest.builder()
                .riderId(riderId)
                .originLat(request.originLat())
                .originLng(request.originLng())
                .destinationLat(request.destinationLat())
                .destinationLng(request.destinationLng())
                .originLabel(DemandService.label(request.originLabel()))
                .destinationLabel(DemandService.label(request.destinationLabel()));

        boolean repeating = request.repeatDays() != null && !request.repeatDays().isEmpty();
        if (repeating) {
            if (request.repeatTime() == null || request.repeatWeeks() == null) {
                throw new BadRequestException("A repeating request needs repeatTime and repeatWeeks");
            }
            LocalTime time = LocalTime.parse(request.repeatTime());
            int mask = request.repeatDays().stream().distinct().mapToInt(GeoMath::dayBit).sum();
            Instant until = GeoMath.lagos(now).toLocalDate().plusWeeks(request.repeatWeeks()).plusDays(1)
                    .atStartOfDay(GeoMath.LAGOS).toInstant();
            builder.daysMask(mask).timeMinutes(time.getHour() * 60 + time.getMinute()).expiresAt(until);
        } else {
            if (request.desiredDepartureTime() == null) {
                throw new BadRequestException("Choose a departure time, or the days and time it repeats");
            }
            Instant expiresAt = request.desiredDepartureTime().plus(matchingProperties.getTimeToleranceMinutes(), ChronoUnit.MINUTES);
            if (!expiresAt.isAfter(now)) {
                throw new BadRequestException("That departure time has already passed");
            }
            builder.desiredDepartureTime(request.desiredDepartureTime()).expiresAt(expiresAt);
        }
        NotifyMeRequest created = builder.build();
        if (notifyMeRequestRepository.findByRiderIdAndCancelledFalseAndFulfilledFalseAndExpiresAtAfterOrderByCreatedAtDesc(riderId, now)
                .stream().anyMatch(existing -> sameRouteAndTime(existing, created))) {
            throw new BadRequestException("You're already waiting on this route around this time");
        }
        return toResponse(notifyMeRequestRepository.save(created));
    }

    /** Both ends close by and the times line up - repeating requests on any shared day, a one-off on one of its days. */
    private boolean sameRouteAndTime(NotifyMeRequest a, NotifyMeRequest b) {
        double radiusKm = demandProperties.getNotifyMeDuplicateRadiusKm();
        if (GeoMath.distanceKm(a.getOriginLat(), a.getOriginLng(), b.getOriginLat(), b.getOriginLng()) > radiusKm
                || GeoMath.distanceKm(a.getDestinationLat(), a.getDestinationLng(), b.getDestinationLat(), b.getDestinationLng()) > radiusKm) {
            return false;
        }
        int window = demandProperties.getNotifyMeDuplicateMinutes();
        if (!a.isRepeating() && !b.isRepeating()) {
            return Duration.between(a.getDesiredDepartureTime(), b.getDesiredDepartureTime()).abs().toMinutes() <= window;
        }
        if (a.isRepeating() && b.isRepeating()) {
            return (a.getDaysMask() & b.getDaysMask()) != 0 && minutesApart(a.getTimeMinutes(), b.getTimeMinutes()) <= window;
        }
        NotifyMeRequest repeating = a.isRepeating() ? a : b;
        Instant oneOffTime = (a.isRepeating() ? b : a).getDesiredDepartureTime();
        ZonedDateTime oneOff = GeoMath.lagos(oneOffTime);
        return oneOffTime.isBefore(repeating.getExpiresAt())
                && (repeating.getDaysMask() & GeoMath.dayBit(oneOff.getDayOfWeek())) != 0
                && minutesApart(repeating.getTimeMinutes(), oneOff.getHour() * 60 + oneOff.getMinute()) <= window;
    }

    @Transactional(readOnly = true)
    public List<NotifyMeResponse> mine(String riderId) {
        return notifyMeRequestRepository
                .findByRiderIdAndCancelledFalseAndFulfilledFalseAndExpiresAtAfterOrderByCreatedAtDesc(riderId, Instant.now())
                .stream().map(NotifyMeService::toResponse).toList();
    }

    /** Someone else's request reads as not found rather than confirming it exists. */
    @Transactional
    public void cancel(String riderId, String requestId) {
        NotifyMeRequest request = notifyMeRequestRepository.findById(requestId)
                .filter(r -> r.getRiderId().equals(riderId))
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        request.setCancelled(true);
        notifyMeRequestRepository.save(request);
    }

    /**
     * Called whenever a ride is posted. A request keeps going until its time passes (one-off) or its last
     * week ends (repeating), so if the first ride it hears about fills up, it still hears about the next.
     * A one-off stops once the rider has a seat on a ride it announced.
     */
    @Transactional
    public void notifyMatching(RoutePost route) {
        Instant now = Instant.now();
        double radiusKm = matchingProperties.getGeoToleranceMeters() / 1000.0;
        int tolerance = matchingProperties.getTimeToleranceMinutes();

        List<NotifyMeRequest> oneOff = notifyMeRequestRepository.findMatching(now,
                route.getDepartureTime().minus(tolerance, ChronoUnit.MINUTES),
                route.getDepartureTime().plus(tolerance, ChronoUnit.MINUTES),
                route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng(), radiusKm);
        for (NotifyMeRequest request : oneOff) {
            if (notifyMeRequestRepository.hasSeatOnAnnouncedRide(request.getId(), request.getRiderId(), BOOKED_NAMES)) {
                request.setFulfilled(true);
                notifyMeRequestRepository.save(request);
                continue;
            }
            tell(request, route);
        }

        ZonedDateTime departure = GeoMath.lagos(route.getDepartureTime());
        int departureMinutes = departure.getHour() * 60 + departure.getMinute();
        notifyMeRequestRepository.findMatchingRepeating(now, GeoMath.dayBit(departure.getDayOfWeek()),
                        route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng(), radiusKm)
                .stream()
                .filter(r -> minutesApart(r.getTimeMinutes(), departureMinutes) <= tolerance)
                .forEach(r -> tell(r, route));
    }

    /**
     * A ride that had filled up has a seat again. Riders we already told about it, who aren't on it, hear
     * once that a seat opened - then the ride goes through the normal match for anyone who asked since.
     */
    @Transactional
    public void notifySeatOpened(RoutePost route) {
        for (NotifyMeRequest request : notifyMeRequestRepository.findToldAboutRide(route.getId(), Instant.now())) {
            if (request.getRiderId().equals(route.getDriverId()) || onRide(request.getRiderId(), route)
                    || notifyMeRequestRepository.claimSeatOpened(request.getId(), route.getId()) == 0) {
                continue;
            }
            send(request, route, true);
        }
        notifyMatching(route);
    }

    private void tell(NotifyMeRequest request, RoutePost route) {
        if (request.getRiderId().equals(route.getDriverId()) || onRide(request.getRiderId(), route)) {
            return;
        }
        if (notifyMeRequestRepository.recordNotification(UUID.randomUUID().toString(), request.getId(), route.getId()) == 0) {
            return;
        }
        send(request, route, false);
    }

    /** Already holding or riding a seat on it - nothing to tell them. */
    private boolean onRide(String riderId, RoutePost route) {
        return bookingRepository.existsByRiderIdAndRoutePostIdAndStatusIn(riderId, route.getId(), BOOKED);
    }

    private void send(NotifyMeRequest request, RoutePost route, boolean seatOpened) {
        kafkaTemplate.send(KafkaTopics.NOTIFY_ME_MATCHED, NotifyMeMatchedEvent.builder()
                .notifyMeRequestId(request.getId())
                .riderId(request.getRiderId())
                .routePostId(route.getId())
                .originLabel(route.getOriginLabel())
                .destinationLabel(route.getDestinationLabel())
                .departureTime(route.getDepartureTime())
                .seatOpened(seatOpened)
                .build());
    }

    private static int minutesApart(int a, int b) {
        int gap = Math.abs(a - b) % (24 * 60);
        return Math.min(gap, 24 * 60 - gap);
    }

    private static NotifyMeResponse toResponse(NotifyMeRequest r) {
        List<DayOfWeek> days = r.isRepeating()
                ? Arrays.stream(DayOfWeek.values()).filter(d -> (r.getDaysMask() & GeoMath.dayBit(d)) != 0).toList()
                : null;
        String time = r.getTimeMinutes() == null ? null : String.format("%02d:%02d", r.getTimeMinutes() / 60, r.getTimeMinutes() % 60);
        return new NotifyMeResponse(r.getId(), r.getOriginLabel(), r.getDestinationLabel(), r.getOriginLat(), r.getOriginLng(),
                r.getDestinationLat(), r.getDestinationLng(), r.getDesiredDepartureTime(), days, time, r.getExpiresAt());
    }
}
