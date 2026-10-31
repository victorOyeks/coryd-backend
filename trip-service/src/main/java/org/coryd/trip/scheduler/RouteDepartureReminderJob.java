package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RouteDepartureReminderEvent;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.service.NearbyDemandService;
import org.coryd.trip.service.TripScheduleService;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Three pushes around departure, each claimed once per route before it's sent, so a Kafka failure
 * misses that one push (logged) instead of re-sending it on every sweep:
 * - the driver's get-ready reminder when the route enters the active window (deep-links to the manifest)
 * - every confirmed rider's reminder 15 minutes out (deep-links to trip details and the pickup code)
 * - "start it or cancel it" to the driver once departure passes unstarted, before RouteExpiryJob
 *   auto-cancels it
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteDepartureReminderJob {

    public static final String DRIVER_GET_READY = "DRIVER_GET_READY";
    public static final String RIDER_GET_READY = "RIDER_GET_READY";
    public static final String DRIVER_START_OVERDUE = "DRIVER_START_OVERDUE";

    private static final List<RouteStatus> NOT_STARTED = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL);
    private static final Duration RIDER_LEAD_TIME = Duration.ofMinutes(15);

    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final NearbyDemandService nearbyDemandService;

    @Scheduled(fixedDelay = 60_000)
    public void sendUpcomingDepartureReminders() {
        Instant now = Instant.now();

        sweep(routePostRepository.findByStatusInAndDepartureTimeBetweenAndDriverReminderSentAtIsNull(
                        NOT_STARTED, now, now.plus(TripScheduleService.ACTIVE_WINDOW)),
                routePostRepository::markDriverReminderSent, now, route -> {
                    // Posted already inside the window ("leave now") - the driver is on the passenger screen already.
                    boolean postedInsideWindow = route.getCreatedAt() != null
                            && route.getCreatedAt().isAfter(route.getDepartureTime().minus(TripScheduleService.ACTIVE_WINDOW));
                    if (postedInsideWindow) {
                        return null;
                    }
                    RouteDepartureReminderEvent ready = event(route, DRIVER_GET_READY, confirmedRiders(route));
                    ready.setWaitingNearby(waitingAlong(route));
                    return ready;
                });

        sweep(routePostRepository.findByStatusInAndDepartureTimeBetweenAndReminderSentAtIsNull(
                        NOT_STARTED, now, now.plus(RIDER_LEAD_TIME)),
                routePostRepository::markReminderSent, now, route -> {
                    List<RouteDepartureReminderEvent.RiderBooking> riders = confirmedRiders(route);
                    return riders.isEmpty() ? null : event(route, RIDER_GET_READY, riders);
                });

        sweep(routePostRepository.findByStatusInAndDepartureTimeBetweenAndStartNudgeSentAtIsNull(
                        NOT_STARTED, now.minus(RouteExpiryJob.NEVER_STARTED_GRACE), now),
                routePostRepository::markStartNudgeSent, now,
                route -> event(route, DRIVER_START_OVERDUE, confirmedRiders(route)));
    }

    private void sweep(List<RoutePost> routes, BiFunction<String, Instant, Integer> claim, Instant now,
                       Function<RoutePost, RouteDepartureReminderEvent> build) {
        for (RoutePost route : routes) {
            try {
                if (claim.apply(route.getId(), now) == 0) {
                    continue;
                }
                RouteDepartureReminderEvent event = build.apply(route);
                if (event != null) {
                    kafkaTemplate.send(KafkaTopics.ROUTE_DEPARTURE_REMINDER, event);
                }
            } catch (Exception e) {
                log.error("Failed to send departure reminder for route {}: {}", route.getId(), e.getMessage(), e);
            }
        }
    }

    /** Best-effort - the reminder still goes without the count. */
    private Integer waitingAlong(RoutePost route) {
        try {
            return nearbyDemandService.waitingAlong(route);
        } catch (Exception e) {
            log.warn("Could not count riders waiting along route {}: {}", route.getId(), e.getMessage());
            return null;
        }
    }

    private List<RouteDepartureReminderEvent.RiderBooking> confirmedRiders(RoutePost route) {
        List<Booking> confirmed = bookingRepository.findByRoutePostIdAndStatus(route.getId(), BookingStatus.CONFIRMED);
        return confirmed.stream()
                .map(b -> new RouteDepartureReminderEvent.RiderBooking(b.getRiderId(), b.getId()))
                .toList();
    }

    private static RouteDepartureReminderEvent event(RoutePost route, String kind,
                                                     List<RouteDepartureReminderEvent.RiderBooking> riders) {
        return RouteDepartureReminderEvent.builder()
                .kind(kind)
                .routePostId(route.getId())
                .driverId(route.getDriverId())
                .originLabel(route.getOriginLabel())
                .destinationLabel(route.getDestinationLabel())
                .departureTime(route.getDepartureTime())
                .riders(riders)
                .build();
    }
}
