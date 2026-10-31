package org.coryd.trip.util;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Small geography and Lagos-time helpers for rider demand. */
public final class GeoMath {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private GeoMath() {
    }

    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Degrees of latitude in km, for a bounding box before the exact distance check. */
    public static double latDegrees(double km) {
        return km / 111.32;
    }

    /** Degrees of longitude in km at this latitude. */
    public static double lngDegrees(double km, double atLat) {
        return km / (111.32 * Math.max(0.01, Math.cos(Math.toRadians(atLat))));
    }

    /**
     * Whether point P lies on the way from A to B, as the crow flies: within corridorKm of the straight line,
     * past the start and not beyond the end. A road bends, so this is only for when there is no road yet -
     * a driver who hasn't posted, heading home.
     */
    public static boolean onTheWay(double aLat, double aLng, double bLat, double bLng, double pLat, double pLng, double corridorKm) {
        double kmPerLng = 111.32 * Math.cos(Math.toRadians(aLat));
        double bx = (bLng - aLng) * kmPerLng;
        double by = (bLat - aLat) * 111.32;
        double px = (pLng - aLng) * kmPerLng;
        double py = (pLat - aLat) * 111.32;
        double length2 = bx * bx + by * by;
        if (length2 < 1e-9) {
            return Math.hypot(px, py) <= corridorKm;
        }
        double t = (px * bx + py * by) / length2;
        if (t <= 0 || t > 1 + corridorKm / Math.sqrt(length2)) {
            return false;
        }
        double tc = Math.min(t, 1);
        return Math.hypot(px - tc * bx, py - tc * by) <= corridorKm;
    }

    /** Groups nearby points: everything in the same square of the given size shares a key. */
    public static String cell(double lat, double lng, double cellDegrees) {
        return (long) Math.floor(lat / cellDegrees) + ":" + (long) Math.floor(lng / cellDegrees);
    }

    public static ZonedDateTime lagos(Instant instant) {
        return instant.atZone(LAGOS);
    }

    public static boolean isWeekend(DayOfWeek day) {
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    /** Mon=1, Tue=2, Wed=4 ... Sun=64. */
    public static int dayBit(DayOfWeek day) {
        return 1 << (day.getValue() - 1);
    }
}
