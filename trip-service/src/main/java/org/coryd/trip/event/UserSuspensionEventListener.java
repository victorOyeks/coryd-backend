package org.coryd.trip.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserReinstatedEvent;
import org.coryd.common.kafka.events.UserSuspendedEvent;
import org.coryd.trip.config.KafkaConfig;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SuspendedUser;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SuspendedUserRepository;
import org.coryd.trip.service.BookingService;
import org.coryd.trip.service.RouteService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Ops suspended someone: they can't post or book from here on, their upcoming routes are cancelled
 * (riders refunded) and their upcoming seats released or refunded. A trip already under way is left
 * to finish - ops deals with that one directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSuspensionEventListener {

    public static final String CANCELLED_BY = "SUSPENDED";
    private static final List<RouteStatus> UPCOMING = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL);

    private final SuspendedUserRepository suspendedUserRepository;
    private final RoutePostRepository routePostRepository;
    private final RouteService routeService;
    private final BookingService bookingService;

    @KafkaListener(topics = KafkaTopics.USER_SUSPENDED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onSuspended(UserSuspendedEvent event) {
        suspendedUserRepository.save(new SuspendedUser(event.getUserId(), event.getReason(),
                event.getSuspendedAt() == null ? Instant.now() : event.getSuspendedAt()));
        for (RoutePost route : routePostRepository.findByDriverIdAndStatusIn(event.getUserId(), UPCOMING)) {
            routeService.cancel(route.getId(), event.getUserId(), CANCELLED_BY);
        }
        bookingService.cancelForSuspension(event.getUserId());
        log.info("User {} suspended - upcoming routes and bookings cancelled", event.getUserId());
    }

    @KafkaListener(topics = KafkaTopics.USER_REINSTATED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onReinstated(UserReinstatedEvent event) {
        suspendedUserRepository.deleteById(event.getUserId());
        log.info("User {} reinstated", event.getUserId());
    }
}
