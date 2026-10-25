package org.coryd.trip.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserDeletedEvent;
import org.coryd.trip.config.KafkaConfig;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.service.BookingService;
import org.coryd.trip.service.RouteService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * An account was deleted. Its upcoming routes and seats are cancelled (riders refunded, as for a
 * suspension), then everything personal trip-service keeps about the person is erased: saved places,
 * searches, notify-me requests, regular routes, driving preferences, blocks either way, and caches.
 * Past routes, bookings, ratings, SOS and safety records stay - they're other people's trips and the
 * accounting trail - but only under a user ID that no longer leads to anyone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionEventListener {

    public static final String CANCELLED_BY = "ACCOUNT_DELETED";
    private static final List<RouteStatus> UPCOMING = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL);

    /** Order matters where one table hangs off another. */
    private static final List<String> ERASE = List.of(
            "DELETE FROM trip.notify_me_notifications WHERE request_id IN (SELECT id FROM trip.notify_me_requests WHERE rider_id = :id)",
            "DELETE FROM trip.notify_me_requests WHERE rider_id = :id",
            "DELETE FROM trip.route_searches WHERE rider_id = :id",
            "DELETE FROM trip.saved_places WHERE user_id = :id",
            "DELETE FROM trip.route_template_seats WHERE rider_id = :id",
            "DELETE FROM trip.route_templates WHERE driver_id = :id",
            "DELETE FROM trip.driver_driving_preferences WHERE driver_id = :id",
            "DELETE FROM trip.demand_alert_log WHERE driver_id = :id",
            "DELETE FROM trip.user_blocks WHERE blocker_id = :id OR blocked_id = :id",
            "DELETE FROM trip.identity_verification_cache WHERE user_id = :id",
            "DELETE FROM trip.driver_verification_cache WHERE driver_id = :id",
            "DELETE FROM trip.suspended_users WHERE user_id = :id");

    private final RoutePostRepository routePostRepository;
    private final RouteService routeService;
    private final BookingService bookingService;
    private final NamedParameterJdbcTemplate jdbc;

    @KafkaListener(topics = KafkaTopics.USER_DELETED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onDeleted(UserDeletedEvent event) {
        String userId = event.getUserId();
        for (RoutePost route : routePostRepository.findByDriverIdAndStatusIn(userId, UPCOMING)) {
            routeService.cancel(route.getId(), userId, CANCELLED_BY);
        }
        bookingService.cancelUpcomingFor(userId, CANCELLED_BY, "Rider closed their account");
        MapSqlParameterSource id = new MapSqlParameterSource("id", userId);
        int erased = ERASE.stream().mapToInt(sql -> jdbc.update(sql, id)).sum();
        log.info("Account {} deleted - upcoming trips cancelled, {} trip records erased", userId, erased);
    }
}
