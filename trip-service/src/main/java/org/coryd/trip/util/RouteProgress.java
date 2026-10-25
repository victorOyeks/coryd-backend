package org.coryd.trip.util;

import java.util.List;

/**
 * How far along a route's road a point is, in metres from the start - so a driver's passenger list can be
 * put in the order the car actually reaches each stop. Straight-line distance from the start gets that
 * wrong on any road that bends back. The route line is decoded once and reused for every passenger.
 */
public final class RouteProgress {

    private final List<PolylineDecoder.LatLng> line;
    private final double lengthMeters;

    private RouteProgress(List<PolylineDecoder.LatLng> line) {
        this.line = line;
        this.lengthMeters = line == null ? 0 : PolylineMath.lengthMeters(line);
    }

    /** A route without a stored line (rare, older trips) gives null for everything - the app keeps its own order. */
    public static RouteProgress of(String encodedPolyline) {
        if (encodedPolyline == null || encodedPolyline.isBlank()) {
            return new RouteProgress(null);
        }
        List<PolylineDecoder.LatLng> points = PolylineDecoder.decode(encodedPolyline);
        return new RouteProgress(points.size() < 2 ? null : points);
    }

    /** Where the rider gets in - 0 when they board at the route's start (no pickup point of their own). */
    public Double pickup(Double lat, Double lng) {
        if (line == null) {
            return null;
        }
        return lat == null || lng == null ? 0.0 : along(lat, lng);
    }

    /** Where the rider gets out - the whole route when they ride to its end. Never before their own pickup. */
    public Double dropoff(Double lat, Double lng, Double pickupMeters) {
        if (line == null) {
            return null;
        }
        double meters = lat == null || lng == null ? Math.round(lengthMeters) : along(lat, lng);
        return pickupMeters == null ? meters : Math.max(meters, pickupMeters);
    }

    private double along(double lat, double lng) {
        return Math.round(PolylineMath.snap(lat, lng, line).fraction() * lengthMeters);
    }
}
