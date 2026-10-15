package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.event.DriverLocationRecordedEvent;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Live trip location - Redis is the source of truth (a rider opening the screen mid-trip needs
 * a "last known point" immediately), the WebSocket broadcast is the live-push layer on top of
 * that. See WebSocketConfig/WebSocketAuthInterceptor for the transport and its auth.
 */
@Service
@RequiredArgsConstructor
public class LocationService {

    private static final String LOCATION_KEY_PREFIX = "trip:location:";
    private static final Duration LOCATION_TTL = Duration.ofMinutes(2);
    private static final int AVAILABLE_BEFORE_DEPARTURE_MINUTES = 30;
    private static final Set<BookingStatus> LOCATION_ELIGIBLE_STATUSES =
            Set.of(BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);
    private static final Set<BookingStatus> NON_TERMINAL_BOOKING_STATUSES =
            Set.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);
    private static final String ROUTE_LOCATION_KEY_PREFIX = "trip:location:route:";

    private final BookingRepository bookingRepository;
    private final RoutePostRepository routePostRepository;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final RouteDeviationService routeDeviationService;
    private final ApplicationEventPublisher eventPublisher;

    /** Dev/staging-only escape hatch for the 30-min-before-departure gate below - never true outside application-dev.yml. */
    @Value("${app.testing.bypass-time-gates:false}")
    private boolean bypassTimeGates;

    /** Driver write path - outside the eligible window is a caller error (the driver app shouldn't be posting yet); posting for a trip that isn't yours is an authorization error. */
    public LocationResponse recordLocation(String bookingId, String callerId, double lat, double lng) {
        RoutePost route = requireEligibleTrip(bookingId);
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can post its location");
        }

        Instant now = Instant.now();
        String value = lat + "," + lng + "," + now.toEpochMilli();
        redisTemplate.opsForValue().set(LOCATION_KEY_PREFIX + bookingId, value, LOCATION_TTL);

        LocationResponse response = new LocationResponse(true, lat, lng, now, null);

        messagingTemplate.convertAndSend("/topic/bookings/" + bookingId + "/location", response);
        return response;
    }

    /** REST read path for the rider/driver's own app - the share-link view (no account, authorized via its signed token instead) calls the unchecked getLocation(bookingId) directly. */
    public LocationResponse getLocationForParty(String bookingId, String callerId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        if (!callerId.equals(booking.getRiderId()) && !callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Not authorized for this trip's location");
        }
        return getLocation(bookingId);
    }

    /** Core lookup - "not available yet" is an expected, common state the client needs to render, not an error to catch. No caller-ownership check here; see getLocationForParty for the authenticated REST path. */
    public LocationResponse getLocation(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        if (!LOCATION_ELIGIBLE_STATUSES.contains(booking.getStatus())) {
            return LocationResponse.unavailable("Live tracking isn't available for this trip right now.");
        }
        Instant opensAt = route.getDepartureTime().minus(AVAILABLE_BEFORE_DEPARTURE_MINUTES, ChronoUnit.MINUTES);
        if (!bypassTimeGates && Instant.now().isBefore(opensAt)) {
            return LocationResponse.unavailable("Live tracking opens 30 minutes before departure.");
        }

        String raw = redisTemplate.opsForValue().get(LOCATION_KEY_PREFIX + bookingId);
        if (raw == null) {
            return LocationResponse.unavailable("Waiting for the driver's location to come through.");
        }

        String[] parts = raw.split(",", 3);
        double lat = Double.parseDouble(parts[0]);
        double lng = Double.parseDouble(parts[1]);
        Instant recordedAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));

        return new LocationResponse(true, lat, lng, recordedAt, null);
    }

    /** Write-path gate - status must be an active trip phase, and it must be within 30 min of departure, matching the screen's own "Available 30 min before departure" copy. */
    private RoutePost requireEligibleTrip(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        RoutePost route = routePostRepository.findById(booking.getRoutePostId())
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        if (!LOCATION_ELIGIBLE_STATUSES.contains(booking.getStatus())) {
            throw new BadRequestException("Live location isn't available for this trip's current status");
        }
        Instant opensAt = route.getDepartureTime().minus(AVAILABLE_BEFORE_DEPARTURE_MINUTES, ChronoUnit.MINUTES);
        if (!bypassTimeGates && Instant.now().isBefore(opensAt)) {
            throw new BadRequestException("Live tracking opens 30 minutes before departure");
        }
        return route;
    }

    /** Driver write path for the Live Trip map - one shared point per route instead of one per passenger, since it's the same physical vehicle. */
    public LocationResponse recordLocationForRoute(String routePostId, String callerId, double lat, double lng) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this route's driver can post its location");
        }
        if (route.getStatus() != RouteStatus.IN_PROGRESS) {
            throw new BadRequestException("Live location isn't available for this trip's current status");
        }

        Instant now = Instant.now();
        String value = lat + "," + lng + "," + now.toEpochMilli();
        redisTemplate.opsForValue().set(ROUTE_LOCATION_KEY_PREFIX + routePostId, value, LOCATION_TTL);

        LocationResponse response = new LocationResponse(true, lat, lng, now, null);

        messagingTemplate.convertAndSend("/topic/routes/" + routePostId + "/location", response);
        routeDeviationService.checkAndHandleDeviation(routePostId, lat, lng);
        eventPublisher.publishEvent(new DriverLocationRecordedEvent(routePostId, lat, lng));
        return response;
    }

    /** REST read path for the Live Trip map - the driver, or any rider with a non-terminal booking on this route. */
    public LocationResponse getLocationForRoute(String routePostId, String callerId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        boolean isDriver = callerId.equals(route.getDriverId());
        boolean isActiveRider = bookingRepository.findByRoutePostId(routePostId).stream()
                .anyMatch(b -> callerId.equals(b.getRiderId()) && NON_TERMINAL_BOOKING_STATUSES.contains(b.getStatus()));
        if (!isDriver && !isActiveRider) {
            throw new UnauthorizedException("Not authorized for this trip's location");
        }

        if (route.getStatus() != RouteStatus.IN_PROGRESS) {
            return LocationResponse.unavailable("Live tracking isn't available for this trip right now.");
        }

        return readRouteLocation(routePostId);
    }

    /** The Ops Console's live map - no participant check, so only reachable through /internal (ops-service). */
    public LocationResponse peekRouteLocation(String routePostId) {
        return readRouteLocation(routePostId);
    }

    private LocationResponse readRouteLocation(String routePostId) {
        String raw = redisTemplate.opsForValue().get(ROUTE_LOCATION_KEY_PREFIX + routePostId);
        if (raw == null) {
            return LocationResponse.unavailable("Waiting for the driver's location to come through.");
        }

        String[] parts = raw.split(",", 3);
        double lat = Double.parseDouble(parts[0]);
        double lng = Double.parseDouble(parts[1]);
        Instant recordedAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));

        return new LocationResponse(true, lat, lng, recordedAt, null);
    }
}
