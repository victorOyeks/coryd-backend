package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.util.GeoUtils;
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

    private final BookingRepository bookingRepository;
    private final RoutePostRepository routePostRepository;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    /** Driver write path - outside the eligible window is a caller error (the driver app shouldn't be posting yet); posting for a trip that isn't yours is an authorization error. */
    public LocationResponse recordLocation(String bookingId, String callerId, double lat, double lng) {
        RoutePost route = requireEligibleTrip(bookingId);
        if (!callerId.equals(route.getDriverId())) {
            throw new UnauthorizedException("Only this trip's driver can post its location");
        }

        Instant now = Instant.now();
        String value = lat + "," + lng + "," + now.toEpochMilli();
        redisTemplate.opsForValue().set(LOCATION_KEY_PREFIX + bookingId, value, LOCATION_TTL);

        LocationResponse response = new LocationResponse(true, lat, lng, now,
                GeoUtils.estimateMinutes(lat, lng, route.getOriginLat(), route.getOriginLng()), null);

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
        if (Instant.now().isBefore(opensAt)) {
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

        return new LocationResponse(true, lat, lng, recordedAt,
                GeoUtils.estimateMinutes(lat, lng, route.getOriginLat(), route.getOriginLng()), null);
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
        if (Instant.now().isBefore(opensAt)) {
            throw new BadRequestException("Live tracking opens 30 minutes before departure");
        }
        return route;
    }
}
