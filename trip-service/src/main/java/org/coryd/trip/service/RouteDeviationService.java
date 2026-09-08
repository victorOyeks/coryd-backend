package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.trip.dto.MultiStopRoutePreview;
import org.coryd.trip.dto.RouteUpdateEvent;
import org.coryd.trip.util.PolylineDecoder;
import org.coryd.trip.util.PolylineDecoder.LatLng;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The plotted route line is computed once, on screen load, and never otherwise touches live
 * location - so a driver who takes a different road than planned leaves the rider staring at a
 * line the car isn't following. This runs on every location ping (LocationService.
 * recordLocationForRoute) and closes that gap in three stages, cheapest first: a free local
 * distance-to-polyline check on every ping; only once a driver is confirmed persistently
 * off-route does a real (billed) Directions recompute fire; and once it does, a cooldown stops
 * continued drift from refetching on every subsequent ping.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteDeviationService {

    private static final double DEVIATION_THRESHOLD_METERS = 250;
    private static final int CONSECUTIVE_PINGS_REQUIRED = 2;
    private static final Duration REROUTE_COOLDOWN = Duration.ofSeconds(75);

    private static final String POLYLINE_KEY_PREFIX = "trip:route:polyline:";
    private static final Duration POLYLINE_TTL = Duration.ofHours(6);
    private static final String DEVIATION_COUNT_KEY_PREFIX = "trip:route:deviation:count:";
    private static final Duration DEVIATION_COUNT_TTL = Duration.ofMinutes(2);
    private static final String REROUTE_COOLDOWN_KEY_PREFIX = "trip:route:reroute:cooldown:";

    private final RouteService routeService;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    /** @Async so this never adds latency to the location-POST request it rides on - the rare
     * reroute path makes a real Maps API call, which must not block the driver's app waiting on
     * a response to what should be a trivial GPS post. Also catches its own failures internally -
     * a Maps hiccup or a Redis blip here must never surface anywhere the caller would notice. */
    @Async("routeDeviationExecutor")
    public void checkAndHandleDeviation(String routePostId, double lat, double lng) {
        try {
            String polylineKey = POLYLINE_KEY_PREFIX + routePostId;
            String activePolyline = redisTemplate.opsForValue().get(polylineKey);

            if (activePolyline == null) {
                seedBaseline(routePostId, polylineKey);
                return;
            }

            List<LatLng> routePoints = PolylineDecoder.decode(activePolyline);
            if (routePoints.size() < 2) {
                return;
            }

            double distanceMeters = distanceToPolylineMeters(lat, lng, routePoints);
            String deviationCountKey = DEVIATION_COUNT_KEY_PREFIX + routePostId;

            if (distanceMeters <= DEVIATION_THRESHOLD_METERS) {
                redisTemplate.delete(deviationCountKey);
                return;
            }

            Long consecutiveOffRoute = redisTemplate.opsForValue().increment(deviationCountKey);
            if (consecutiveOffRoute != null && consecutiveOffRoute == 1) {
                redisTemplate.expire(deviationCountKey, DEVIATION_COUNT_TTL);
            }
            if (consecutiveOffRoute == null || consecutiveOffRoute < CONSECUTIVE_PINGS_REQUIRED) {
                return;
            }

            String cooldownKey = REROUTE_COOLDOWN_KEY_PREFIX + routePostId;
            boolean acquiredCooldown = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(cooldownKey, Instant.now().toString(), REROUTE_COOLDOWN));
            if (!acquiredCooldown) {
                return;
            }

            reroute(routePostId, lat, lng, polylineKey);
        } catch (Exception e) {
            log.warn("Deviation check failed for route {}: {}", routePostId, e.getMessage());
        }
    }

    /** First ping for a trip with no baseline yet - nothing to compare against, so just cache the
     * planned line and skip the check this once. */
    private void seedBaseline(String routePostId, String polylineKey) {
        MultiStopRoutePreview preview = routeService.getMultiStopRoutePreview(routePostId);
        if (preview.overviewPolyline() != null) {
            redisTemplate.opsForValue().set(polylineKey, preview.overviewPolyline(), POLYLINE_TTL);
        }
    }

    private void reroute(String routePostId, double lat, double lng, String polylineKey) {
        MultiStopRoutePreview preview = routeService.getMultiStopRoutePreview(routePostId, lat, lng);
        if (preview.overviewPolyline() == null) {
            return;
        }
        redisTemplate.opsForValue().set(polylineKey, preview.overviewPolyline(), POLYLINE_TTL);
        log.info("Route {} rerouted - driver deviated from the plotted line, recomputed from {},{}", routePostId, lat, lng);

        messagingTemplate.convertAndSend("/topic/routes/" + routePostId + "/route-update",
                new RouteUpdateEvent(routePostId, preview.overviewPolyline(), preview.distanceKm(),
                        preview.durationMinutes(), Instant.now()));
    }

    /** Nearest distance from a point to any SEGMENT of the polyline, not just the nearest vertex -
     * overview polylines have long, sparse stretches on highways where a vertex-only check would
     * false-positive a deviation the driver never actually made. */
    private double distanceToPolylineMeters(double lat, double lng, List<LatLng> routePoints) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < routePoints.size() - 1; i++) {
            double d = distanceToSegmentMeters(lat, lng, routePoints.get(i), routePoints.get(i + 1));
            if (d < min) {
                min = d;
            }
        }
        return min;
    }

    /** Projects the point onto the segment (flat-plane approximation - fine at the scale of two
     * adjacent polyline vertices) and measures the real Haversine distance to the closest point
     * on it, clamped to the segment's endpoints. */
    private double distanceToSegmentMeters(double lat, double lng, LatLng a, LatLng b) {
        double dx = b.lng() - a.lng();
        double dy = b.lat() - a.lat();
        double lengthSquared = dx * dx + dy * dy;

        double t = lengthSquared == 0 ? 0 : ((lng - a.lng()) * dx + (lat - a.lat()) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));

        double closestLng = a.lng() + t * dx;
        double closestLat = a.lat() + t * dy;

        return haversineMeters(lat, lng, closestLat, closestLng);
    }

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusMeters = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMeters * c;
    }
}
