package org.coryd.trip.util;

/** Shared great-circle distance/ETA math - used by RouteService (pickup-eta on search results) and LocationService (live-location ETA), so it lives in one place instead of two copies drifting apart. */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** Assumed average city driving speed for ETA estimates - same technique as marketrun-ng's DeliveryPricingService.DELIVERY_SPEED_KM_H, to avoid a Google Directions call per estimate. */
    public static final double ASSUMED_SPEED_KM_H = 25.0;

    private GeoUtils() {
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static int estimateMinutes(double lat1, double lng1, double lat2, double lng2) {
        return (int) Math.ceil(haversineKm(lat1, lng1, lat2, lng2) / ASSUMED_SPEED_KM_H * 60);
    }
}
