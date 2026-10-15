package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.util.PolylineDecoder;
import org.coryd.trip.util.PolylineMath;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Along-the-way matching: a rider matches a route when their start and their end are both within a
 * walk of the driver's road, and the start comes first. The driver never leaves the road - the
 * pickup and drop-off are the nearest points on it, and the rider walks there.
 *
 * <p>The road is the Directions line saved when the route was posted; older routes without one use
 * the straight line between its ends. Pickup time is departure plus the share of the trip's driving
 * time it takes to get that far along.
 */
@Component
@RequiredArgsConstructor
public class RouteMatcher {

    /** Used when Maps gave no driving time - Lagos traffic, roughly. */
    private static final double FALLBACK_KMH = 25;

    private final MatchingProperties properties;

    /** Where the rider meets the car and leaves it, and when. walkMeters is from the rider's own point. */
    public record Match(Point pickup, Point dropoff) {
    }

    public record Point(double lat, double lng, int walkMeters, double fraction, Instant time) {
    }

    public boolean enabled() {
        return properties.isAlongRouteEnabled();
    }

    public Optional<Match> match(RoutePost route, double originLat, double originLng, double destinationLat, double destinationLng) {
        List<PolylineDecoder.LatLng> line = line(route);
        PolylineMath.Snap pickup = PolylineMath.snap(originLat, originLng, line);
        PolylineMath.Snap dropoff = PolylineMath.snap(destinationLat, destinationLng, line);
        int walk = properties.getWalkMeters();
        if (pickup.distanceMeters() > walk || dropoff.distanceMeters() > walk || pickup.fraction() >= dropoff.fraction()) {
            return Optional.empty();
        }
        double minutes = durationMinutes(route, line);
        return Optional.of(new Match(point(route, pickup, minutes), point(route, dropoff, minutes)));
    }

    /** How far a point is from the driver's road - a pickup must be on it, give or take the walk. */
    public double metersFromRoute(RoutePost route, double lat, double lng) {
        return PolylineMath.snap(lat, lng, line(route)).distanceMeters();
    }

    private static Point point(RoutePost route, PolylineMath.Snap snap, double tripMinutes) {
        Instant time = route.getDepartureTime().plusSeconds(Math.round(snap.fraction() * tripMinutes * 60));
        return new Point(snap.lat(), snap.lng(), (int) Math.round(snap.distanceMeters()), snap.fraction(), time);
    }

    private static List<PolylineDecoder.LatLng> line(RoutePost route) {
        List<PolylineDecoder.LatLng> decoded = PolylineDecoder.decode(route.getRoutePolyline());
        return decoded.size() >= 2 ? decoded : List.of(
                new PolylineDecoder.LatLng(route.getOriginLat(), route.getOriginLng()),
                new PolylineDecoder.LatLng(route.getDestinationLat(), route.getDestinationLng()));
    }

    private static double durationMinutes(RoutePost route, List<PolylineDecoder.LatLng> line) {
        if (route.getEstimatedDurationMinutes() != null && route.getEstimatedDurationMinutes() > 0) {
            return route.getEstimatedDurationMinutes();
        }
        return PolylineMath.lengthMeters(line) / 1000 / FALLBACK_KMH * 60;
    }
}
