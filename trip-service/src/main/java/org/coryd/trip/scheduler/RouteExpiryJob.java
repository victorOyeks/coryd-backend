package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.config.StuckTripProperties;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.service.RouteService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Nothing else in the system ever revisits a route once its departureTime has passed - only a
 * driver tapping Start/Complete moves it forward, so a driver who never opens the app again
 * leaves it PUBLISHED/FULL (or IN_PROGRESS) forever, still showing as a live booking to anyone
 * who already paid for a seat on it. This sweeps both flavors of that:
 *
 * 1. Never started - PUBLISHED/FULL past a grace window past departure. Cancelling here is safe
 *    to do automatically: the trip demonstrably never began, so RouteService.cancel()'s existing
 *    refund-and-notify cascade is exactly the right, unambiguous outcome.
 * 2. Started but never finished - IN_PROGRESS past its expected arrival plus StuckTripProperties'
 *    grace. This one is NOT auto-resolved: there's no way to tell from data alone whether the trip
 *    actually happened and the driver just forgot to tap Complete, or whether it never happened at
 *    all - completing it would risk charging a rider for nothing, cancelling it would risk denying
 *    the driver a real payout. The Ops Console's stuck-trips queue (GET /ops/trips/stuck) lists
 *    these and ops resolves them there; this only logs them as well.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteExpiryJob {

    private static final List<RouteStatus> NEVER_STARTED_STATUSES = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL);
    static final Duration NEVER_STARTED_GRACE = Duration.ofMinutes(45);

    private final RoutePostRepository routePostRepository;
    private final RouteService routeService;
    private final StuckTripProperties stuckTripProperties;

    @Scheduled(fixedDelay = 5 * 60_000)
    public void run() {
        expireNeverStarted();
        flagStuckInProgress();
    }

    private void expireNeverStarted() {
        Instant cutoff = Instant.now().minus(NEVER_STARTED_GRACE);
        List<RoutePost> overdue = routePostRepository.findByStatusInAndDepartureTimeBefore(NEVER_STARTED_STATUSES, cutoff);
        for (RoutePost route : overdue) {
            try {
                routeService.cancel(route.getId(), route.getDriverId(), RouteService.CANCELLED_NOT_STARTED);
                log.warn("Auto-cancelled route {} (driver {}) - never started, departure was {}",
                        route.getId(), route.getDriverId(), route.getDepartureTime());
            } catch (Exception e) {
                // One bad route (e.g. already cancelled by the driver a second before this ran)
                // shouldn't stop the rest of the sweep - each cancel() call is its own transaction.
                log.error("Failed to auto-cancel overdue route {}: {}", route.getId(), e.getMessage(), e);
            }
        }
    }

    private void flagStuckInProgress() {
        Instant now = Instant.now();
        for (RoutePost route : routePostRepository.findByStatus(RouteStatus.IN_PROGRESS)) {
            if (stuckTripProperties.isStuck(route, now)) {
                log.warn("OPS REVIEW NEEDED: route {} (driver {}) is still IN_PROGRESS {}h past its expected arrival {} - "
                                + "resolve it from the Ops Console's stuck trips (complete or cancel).",
                        route.getId(), route.getDriverId(), stuckTripProperties.getGraceHours(),
                        stuckTripProperties.expectedArrival(route));
            }
        }
    }
}
