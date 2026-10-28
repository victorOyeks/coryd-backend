package org.coryd.trip.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.trip.client.GoogleMapsClient;
import org.coryd.trip.dto.DirectionsMultiStopResult;
import org.coryd.trip.dto.DistanceMatrixResult;
import org.coryd.trip.dto.GeocodeResult;
import org.coryd.trip.dto.PlaceSuggestion;
import org.coryd.trip.dto.RoutePreview;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Adds Redis caching and a Redis-backed circuit breaker around GoogleMapsClient so a
 * Maps Platform outage degrades to cached data / a clear error instead of cascading
 * failures into route posting and search.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapsService {

    private final GoogleMapsClient googleMapsClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PLACE_COORDS_PREFIX = "place:coords:";
    private static final Duration PLACE_COORDS_TTL   = Duration.ofDays(30);
    private static final String ROUTE_PREVIEW_PREFIX = "route:preview:";
    private static final Duration ROUTE_PREVIEW_TTL  = Duration.ofDays(7);
    private static final String ROUTE_OPTIONS_PREFIX = "route:options:";
    private static final Duration ROUTE_OPTIONS_TTL  = Duration.ofDays(1);

    private static final String MAPS_FAILURES_KEY   = "maps:circuit:failures";
    private static final int CIRCUIT_OPEN_THRESHOLD = 5;
    private static final Duration CIRCUIT_RESET_TTL = Duration.ofMinutes(1);

    public List<PlaceSuggestion> autocomplete(String input) {
        if (input == null || input.isBlank()) return List.of();
        try {
            return googleMapsClient.autocomplete(input);
        } catch (Exception e) {
            log.error("Places Autocomplete call failed: {}", e.getMessage());
            return List.of();
        }
    }

    public GeocodeResult reverseGeocode(double lat, double lng) {
        try {
            return googleMapsClient.reverseGeocode(lat, lng);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("Reverse geocoding failed for {},{}: {}", lat, lng, e.getMessage());
            throw new BadRequestException("Geocoding service error - please try again");
        }
    }

    public GeocodeResult getPlaceDetails(String placeId) {
        if (placeId == null || placeId.isBlank()) throw new BadRequestException("placeId is required");

        String cacheKey = PLACE_COORDS_PREFIX + placeId;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                String[] parts = cached.split("\\|", 3);
                if (parts.length == 3) {
                    return new GeocodeResult(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
                }
            }
        } catch (Exception e) {
            log.warn("Place coords cache read failed for {}: {}", placeId, e.getMessage());
        }

        if (isMapsCircuitOpen()) {
            log.warn("Maps circuit open - rejecting place-details request for placeId={}", placeId);
            throw new BadRequestException("Maps service is temporarily unavailable. Please try again in a moment.");
        }

        try {
            GeocodeResult details = googleMapsClient.getPlaceDetails(placeId);
            resetMapsCircuit();
            try {
                String encoded = String.join("|", details.formattedAddress(),
                        String.valueOf(details.lat()), String.valueOf(details.lng()));
                redisTemplate.opsForValue().set(cacheKey, encoded, PLACE_COORDS_TTL);
            } catch (Exception e) {
                log.warn("Place coords cache write failed for {}: {}", placeId, e.getMessage());
            }
            return details;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            recordMapsFailure();
            log.error("Google Maps API call failed for placeId={}: {}", placeId, e.getMessage());
            throw new BadRequestException("Unable to load place details. Please try again.");
        }
    }

    public RoutePreview getRoutePreview(double originLat, double originLng, double destLat, double destLng) {
        String cacheKey = buildRouteCacheKey(originLat, originLng, destLat, destLng);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                // limit=5: overviewPolyline (last field) is Google's encoded-polyline format,
                // which can itself contain a literal "|" - splitting with a limit keeps
                // everything past the 4th delimiter intact as one final part.
                String[] parts = cached.split("\\|", 5);
                if (parts.length == 5) {
                    return new RoutePreview(Double.parseDouble(parts[0]), Integer.parseInt(parts[1]), parts[2], parts[3], parts[4]);
                }
            }
        } catch (Exception e) {
            log.warn("Route preview cache read failed: {}", e.getMessage());
        }

        if (isMapsCircuitOpen()) {
            log.warn("Maps circuit open - rejecting route-preview request");
            throw new BadRequestException("Maps service is temporarily unavailable. Please try again in a moment.");
        }

        try {
            RoutePreview preview = googleMapsClient.getRoutePreview(originLat, originLng, destLat, destLng);
            resetMapsCircuit();
            try {
                String encoded = String.join("|",
                        String.valueOf(preview.distanceKm()), String.valueOf(preview.durationMinutes()),
                        nullSafe(preview.durationText()), nullSafe(preview.viaSummary()), nullSafe(preview.overviewPolyline()));
                redisTemplate.opsForValue().set(cacheKey, encoded, ROUTE_PREVIEW_TTL);
            } catch (Exception e) {
                log.warn("Route preview cache write failed: {}", e.getMessage());
            }
            return preview;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            recordMapsFailure();
            log.error("Directions API call failed: {}", e.getMessage());
            throw new BadRequestException("Unable to load route preview. Please try again.");
        }
    }

    /** The driver/rider Live Trip map's multi-stop line. Not cached: keyed to a trip's current
     * set of active pickups, which changes as the trip proceeds (a pickup confirmed, a booking
     * cancelled), so a cached result would go stale mid-trip exactly when it matters most. */
    public DirectionsMultiStopResult getMultiStopRoutePreview(double originLat, double originLng,
                                                               double destLat, double destLng,
                                                               List<double[]> waypointCoords) {
        if (isMapsCircuitOpen()) {
            log.warn("Maps circuit open - rejecting multi-stop route-preview request");
            throw new BadRequestException("Maps service is temporarily unavailable. Please try again in a moment.");
        }

        try {
            DirectionsMultiStopResult result = googleMapsClient.getMultiStopRoutePreview(
                    originLat, originLng, destLat, destLng, waypointCoords);
            resetMapsCircuit();
            return result;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            recordMapsFailure();
            log.error("Multi-stop Directions API call failed: {}", e.getMessage());
            throw new BadRequestException("Unable to load multi-stop route preview. Please try again.");
        }
    }

    /** The driver's road choices while posting a route. Google offers alternatives only when its traffic picture
     * suggests them, so the same two points can come back with three roads one minute and one the next. A choice of
     * two or more is kept for a day so the screen shows the same roads each time it opens; a lone road isn't kept,
     * so the next open asks again. With via points the combinations hardly ever repeat, so those aren't cached. */
    public List<RoutePreview> getRouteOptions(double originLat, double originLng, double destLat, double destLng,
                                              List<double[]> via) {
        String cacheKey = via.isEmpty() ? buildOptionsCacheKey(originLat, originLng, destLat, destLng) : null;
        if (cacheKey != null) {
            try {
                String cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    return objectMapper.readValue(cached, new TypeReference<List<RoutePreview>>() { });
                }
            } catch (Exception e) {
                log.warn("Route options cache read failed: {}", e.getMessage());
            }
        }

        if (isMapsCircuitOpen()) {
            log.warn("Maps circuit open - rejecting route-options request");
            throw new BadRequestException("Maps service is temporarily unavailable. Please try again in a moment.");
        }
        List<RoutePreview> options;
        try {
            options = googleMapsClient.getRouteOptions(originLat, originLng, destLat, destLng, via);
            resetMapsCircuit();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            recordMapsFailure();
            log.error("Route options call failed: {}", e.getMessage());
            throw new BadRequestException("Unable to load roads. Please try again.");
        }

        if (cacheKey != null && options.size() > 1) {
            try {
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(options), ROUTE_OPTIONS_TTL);
            } catch (Exception e) {
                log.warn("Route options cache write failed: {}", e.getMessage());
            }
        }
        return options;
    }

    /** The search-results list's pickup-ETA column - one batched call across every result row instead of one Directions call per row. Not cached: a batch is keyed by wherever this particular rider is standing right now against N route origins, which essentially never repeats, so caching would just waste Redis writes. */
    public List<DistanceMatrixResult> getDistanceMatrixBatch(double originLat, double originLng, List<double[]> destinations) {
        if (isMapsCircuitOpen()) {
            log.warn("Maps circuit open - rejecting distance-matrix batch request");
            throw new BadRequestException("Maps service is temporarily unavailable. Please try again in a moment.");
        }

        try {
            List<DistanceMatrixResult> results = googleMapsClient.getDistanceMatrix(originLat, originLng, destinations);
            resetMapsCircuit();
            return results;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            recordMapsFailure();
            log.error("Distance Matrix batch call failed: {}", e.getMessage());
            throw new BadRequestException("Unable to load pickup times. Please try again.");
        }
    }

    private boolean isMapsCircuitOpen() {
        try {
            String val = redisTemplate.opsForValue().get(MAPS_FAILURES_KEY);
            return val != null && Integer.parseInt(val) >= CIRCUIT_OPEN_THRESHOLD;
        } catch (Exception e) {
            return false;
        }
    }

    private void recordMapsFailure() {
        try {
            Long count = redisTemplate.opsForValue().increment(MAPS_FAILURES_KEY);
            if (count != null && count == 1) {
                redisTemplate.expire(MAPS_FAILURES_KEY, CIRCUIT_RESET_TTL);
            }
            if (count != null && count >= CIRCUIT_OPEN_THRESHOLD) {
                log.warn("Maps circuit tripped after {} consecutive failures - will reset in {}s",
                        count, CIRCUIT_RESET_TTL.toSeconds());
            }
        } catch (Exception ignored) {
        }
    }

    private void resetMapsCircuit() {
        try {
            redisTemplate.delete(MAPS_FAILURES_KEY);
        } catch (Exception ignored) {
        }
    }

    private static String buildRouteCacheKey(double lat1, double lng1, double lat2, double lng2) {
        return ROUTE_PREVIEW_PREFIX
                + Math.round(lat1 * 10000) + "," + Math.round(lng1 * 10000)
                + ":" + Math.round(lat2 * 10000) + "," + Math.round(lng2 * 10000);
    }

    private static String buildOptionsCacheKey(double lat1, double lng1, double lat2, double lng2) {
        return ROUTE_OPTIONS_PREFIX + buildRouteCacheKey(lat1, lng1, lat2, lng2).substring(ROUTE_PREVIEW_PREFIX.length());
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
