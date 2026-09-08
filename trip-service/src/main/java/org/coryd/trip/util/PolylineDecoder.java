package org.coryd.trip.util;

import java.util.ArrayList;
import java.util.List;

/** Decodes Google's polyline encoding (Directions API's overview_polyline.points) into raw lat/lng points. */
public final class PolylineDecoder {

    private PolylineDecoder() {
    }

    public record LatLng(double lat, double lng) {
    }

    public static List<LatLng> decode(String encoded) {
        List<LatLng> points = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return points;
        }

        int index = 0;
        int len = encoded.length();
        int lat = 0;
        int lng = 0;

        while (index < len) {
            int shift = 0;
            int result = 0;
            int b;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lat += ((result & 1) != 0) ? ~(result >> 1) : (result >> 1);

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lng += ((result & 1) != 0) ? ~(result >> 1) : (result >> 1);

            points.add(new LatLng(lat / 1e5, lng / 1e5));
        }
        return points;
    }
}
