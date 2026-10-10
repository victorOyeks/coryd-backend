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
