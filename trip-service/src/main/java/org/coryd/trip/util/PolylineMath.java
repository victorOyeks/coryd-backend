package org.coryd.trip.util;

import java.util.List;

/** Where a point sits against a road line: the nearest point on it, how far off it is, and how far along. */
public final class PolylineMath {

    private PolylineMath() {
    }

    /** fraction is 0 at the line's start and 1 at its end, by distance along the road. */
    public record Snap(double lat, double lng, double distanceMeters, double fraction) {
    }

    public static Snap snap(double lat, double lng, List<PolylineDecoder.LatLng> line) {
        double total = 0;
        double[] segment = new double[line.size() - 1];
        for (int i = 0; i < segment.length; i++) {
            segment[i] = meters(line.get(i).lat(), line.get(i).lng(), line.get(i + 1).lat(), line.get(i + 1).lng());
            total += segment[i];
        }
        Snap best = null;
        double along = 0;
        for (int i = 0; i < segment.length; i++) {
            PolylineDecoder.LatLng a = line.get(i);
            PolylineDecoder.LatLng b = line.get(i + 1);
            double dx = b.lng() - a.lng();
            double dy = b.lat() - a.lat();
            double lengthSquared = dx * dx + dy * dy;
            double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((lng - a.lng()) * dx + (lat - a.lat()) * dy) / lengthSquared));
            double pLat = a.lat() + t * dy;
            double pLng = a.lng() + t * dx;
            double off = meters(lat, lng, pLat, pLng);
            if (best == null || off < best.distanceMeters()) {
                best = new Snap(pLat, pLng, off, total == 0 ? 0 : (along + t * segment[i]) / total);
            }
            along += segment[i];
        }
        return best;
    }

    public static double lengthMeters(List<PolylineDecoder.LatLng> line) {
        double total = 0;
        for (int i = 0; i < line.size() - 1; i++) {
            total += meters(line.get(i).lat(), line.get(i).lng(), line.get(i + 1).lat(), line.get(i + 1).lng());
        }
        return total;
    }

    private static double meters(double lat1, double lng1, double lat2, double lng2) {
        return GeoMath.distanceKm(lat1, lng1, lat2, lng2) * 1000;
    }
}
