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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * "Almost time" push for both the driver and every confirmed rider, 15 minutes before departure.
 * markReminderSent() is claimed atomically per route before anything else happens, same as
 * RouteExpiryJob's per-item try/catch - a Kafka failure on one route just misses that one
 * reminder (logged) instead of leaving the route re-checked, and possibly re-sent, forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteDepartureReminderJob {

    private static final List<RouteStatus> REMINDABLE_STATUSES = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL);
    private static final Duration REMINDER_LEAD_TIME = Duration.ofMinutes(15);

    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(fixedDelay = 60_000)
    public void sendUpcomingDepartureReminders() {
        Instant now = Instant.now();
        List<RoutePost> upcoming = routePostRepository.findByStatusInAndDepartureTimeBetweenAndReminderSentAtIsNull(
                REMINDABLE_STATUSES, now, now.plus(REMINDER_LEAD_TIME));

        for (RoutePost route : upcoming) {
            try {
                remind(route, now);
            } catch (Exception e) {
                log.error("Failed to send departure reminder for route {}: {}", route.getId(), e.getMessage(), e);
            }
        }
    }

    private void remind(RoutePost route, Instant now) {
        if (routePostRepository.markReminderSent(route.getId(), now) == 0) {
            return;
        }

        List<Booking> confirmed = bookingRepository.findByRoutePostIdAndStatus(route.getId(), BookingStatus.CONFIRMED);
        if (confirmed.isEmpty()) {
            return;
        }

        List<RouteDepartureReminderEvent.RiderBooking> riders = confirmed.stream()
                .map(b -> new RouteDepartureReminderEvent.RiderBooking(b.getRiderId(), b.getId()))
                .toList();

        kafkaTemplate.send(KafkaTopics.ROUTE_DEPARTURE_REMINDER, RouteDepartureReminderEvent.builder()
                .routePostId(route.getId())
                .driverId(route.getDriverId())
                .originLabel(route.getOriginLabel())
                .destinationLabel(route.getDestinationLabel())
                .departureTime(route.getDepartureTime())
                .riders(riders)
                .build());
    }
}
