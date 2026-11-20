package org.coryd.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.dto.BookingSummary;
import org.coryd.trip.dto.DistanceMatrixResult;
import org.coryd.trip.dto.DriverActiveTripResponse;
import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.dto.NextStop;
import org.coryd.trip.util.GeoMath;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Driver Home's "· 4 min away" on the trip card. Picks the next stop the way the app does, then asks Google's
 * Distance Matrix (through MapsService and its circuit breaker) how far the driver's live point is from it.
 *
 * Home polls every 20 seconds, so the answer is kept in Redis per route and reused until it is a minute old, the
 * car has moved a few hundred metres from where it was worked out, or the next stop changes. When Google can't
 * answer, a straight-line guess at Lagos city speed stands in (marked estimated).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NextStopEtaService {

    static final String CACHE_PREFIX = "trip:next-stop:";
    /** Reuse an answer for this long... */
    static final Duration RECOMPUTE_AFTER = Duration.ofSeconds(60);
    /** ...unless the car has moved this far since. */
    static final double MOVED_METERS = 400;
    /** A drop-off further than this from the destination is a stop of its own - the app's getsOutEarly() uses 0.2 km too. */
    static final double SAME_PLACE_KM = 0.2;
    /** Straight-line fallback: roads are about this much longer than the crow flies... */
    static final double ROAD_FACTOR = 1.3;
    /** ...driven at a typical Lagos in-town speed. */
    static final double CITY_KMH = 20;

    private static final List<BookingStatus> AWAITING_PICKUP =
            List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP);

    private final LocationService locationService;
    private final MapsService mapsService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    /** Null before the trip starts or while there's no live point from the driver's phone. */
    public NextStop forTrip(DriverActiveTripResponse trip) {
        if (trip.status() != RouteStatus.IN_PROGRESS) {
            return null;
        }
        try {
            LocationResponse here = locationService.peekRouteLocation(trip.routePostId());
            if (here == null || !here.available() || here.lat() == null || here.lng() == null) {
                return null;
            }
            Target target = chooseTarget(trip);
            Cached cached = readCache(trip.routePostId());
            Instant now = clock.instant();
            if (cached != null && cached.sameTarget(target)
                    && cached.stop().computedAt().plus(RECOMPUTE_AFTER).isAfter(now)
                    && GeoMath.distanceKm(cached.fromLat(), cached.fromLng(), here.lat(), here.lng()) * 1000 < MOVED_METERS) {
                return cached.stop();
            }
            NextStop stop = compute(target, here.lat(), here.lng(), now);
            writeCache(trip.routePostId(), new Cached(here.lat(), here.lng(), stop));
            return stop;
        } catch (Exception e) {
            // The card is fine without an ETA - never fail the whole /active call over it.
            log.warn("Next-stop ETA failed for route {}: {}", trip.routePostId(), e.getMessage());
            return null;
        }
    }

    record Target(NextStop.Kind kind, String bookingId, String label, double lat, double lng) {
    }

    /**
     * Same order as the app (tripStops.activeBookingsInStopOrder + summariseDriverTrip): riders sorted by how far
     * along the route they board, then get out; the first still waiting to be collected; else the rider aboard who
     * gets out soonest before the end; else the destination. The bookings here are already the active ones only.
     */
    static Target chooseTarget(DriverActiveTripResponse trip) {
        List<BookingSummary> bookings = trip.bookings() == null ? List.of() : trip.bookings();
        List<BookingSummary> ordered = bookings.stream().anyMatch(b -> b.pickupAlongRouteMeters() != null)
                ? bookings.stream()
                    .sorted(Comparator.comparingDouble((BookingSummary b) -> along(b.pickupAlongRouteMeters()))
                            .thenComparingDouble(b -> along(b.dropoffAlongRouteMeters())))
                    .toList()
                : bookings;

        Optional<BookingSummary> pickup = ordered.stream()
                .filter(b -> AWAITING_PICKUP.contains(b.status()))
                .filter(b -> b.pickupLat() != null && b.pickupLng() != null)
                .findFirst();
        if (pickup.isPresent()) {
            BookingSummary b = pickup.get();
            return new Target(NextStop.Kind.PICKUP, b.bookingId(), b.pickupLabel(), b.pickupLat(), b.pickupLng());
        }
        Optional<BookingSummary> dropoff = ordered.stream()
                .filter(b -> b.status() == BookingStatus.IN_PROGRESS)
                .filter(b -> getsOutEarly(b, trip))
                .min(Comparator.comparingDouble(b -> along(b.dropoffAlongRouteMeters())));
        if (dropoff.isPresent()) {
            BookingSummary b = dropoff.get();
            return new Target(NextStop.Kind.DROPOFF, b.bookingId(), b.dropoffLabel(), b.dropoffLat(), b.dropoffLng());
        }
        return new Target(NextStop.Kind.DESTINATION, null, trip.destinationLabel(), trip.destinationLat(), trip.destinationLng());
    }

    private static boolean getsOutEarly(BookingSummary b, DriverActiveTripResponse trip) {
        if (b.dropoffLabel() == null || b.dropoffLat() == null || b.dropoffLng() == null) {
            return false;
        }
        return GeoMath.distanceKm(b.dropoffLat(), b.dropoffLng(), trip.destinationLat(), trip.destinationLng()) > SAME_PLACE_KM;
    }

    private static double along(Double meters) {
        return meters == null ? Double.MAX_VALUE : meters;
    }

    private NextStop compute(Target target, double fromLat, double fromLng, Instant now) {
        try {
            List<DistanceMatrixResult> rows = mapsService.getDistanceMatrixBatch(fromLat, fromLng,
                    List.of(new double[]{target.lat(), target.lng()}));
            DistanceMatrixResult row = rows.isEmpty() ? null : rows.getFirst();
            if (row != null && row.distanceKm() != null && row.durationMinutes() != null) {
                return new NextStop(target.kind(), target.bookingId(), target.label(), target.lat(), target.lng(),
                        Math.max(1, row.durationMinutes()), (int) Math.round(row.distanceKm() * 1000), false, now);
            }
        } catch (Exception e) {
            log.info("Distance Matrix unavailable for next stop, using a straight-line estimate: {}", e.getMessage());
        }
        return estimate(target, fromLat, fromLng, now);
    }

    static NextStop estimate(Target target, double fromLat, double fromLng, Instant now) {
        double roadKm = GeoMath.distanceKm(fromLat, fromLng, target.lat(), target.lng()) * ROAD_FACTOR;
        int minutes = (int) Math.max(1, Math.ceil(roadKm / CITY_KMH * 60));
        return new NextStop(target.kind(), target.bookingId(), target.label(), target.lat(), target.lng(),
                minutes, (int) Math.round(roadKm * 1000), true, now);
    }

    record Cached(double fromLat, double fromLng, NextStop stop) {
        boolean sameTarget(Target t) {
            return stop.kind() == t.kind() && Objects.equals(stop.bookingId(), t.bookingId())
                    && stop.lat() == t.lat() && stop.lng() == t.lng();
        }
    }

    private Cached readCache(String routePostId) {
        try {
            String raw = redisTemplate.opsForValue().get(CACHE_PREFIX + routePostId);
            return raw == null ? null : objectMapper.readValue(raw, Cached.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(String routePostId, Cached value) {
        try {
            redisTemplate.opsForValue().set(CACHE_PREFIX + routePostId, objectMapper.writeValueAsString(value), Duration.ofMinutes(10));
        } catch (Exception e) {
            log.warn("Next-stop cache write failed for route {}: {}", routePostId, e.getMessage());
        }
    }
}
