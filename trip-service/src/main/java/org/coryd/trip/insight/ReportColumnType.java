package org.coryd.trip.insight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one declared type of every report column (docs/ops-console/dashboard-reports.md), and the value each row holds for
 * it: integer and decimal are JSON numbers, money a number in naira with 2 decimal places, date Lagos local
 * "YYYY-MM-DD HH:MM" text, string text, boolean true/false. Kept apart from TripReports so the queries and the types don't
 * churn together; a test checks every column of every report has one.
 */
public enum ReportColumnType {
    INTEGER, DECIMAL, MONEY, DATE, STRING, BOOLEAN;

    /** "integer", "money"... - how the catalog and preview name it. */
    public String label() {
        return name().toLowerCase();
    }

    private static final Set<String> INTEGERS = Set.of("seat_capacity", "seats_booked", "seats", "minutes_before_departure",
            "trips_posted", "trips_completed", "trips_cancelled", "seats_offered", "ratings", "bookings", "completed",
            "cancelled_by_rider", "cancelled_by_driver", "no_shows", "trips", "drivers", "suggested_sample", "payments", "people");
    private static final Set<String> DECIMALS = Set.of("completion_rate_pct", "fill_rate_pct", "avg_rating", "percent_of_joined");
    private static final Set<String> MONEY_COLUMNS = Set.of("fare_per_seat", "gross_completed", "fare_total", "fee", "refund_expected",
            "spent_completed", "median_fare", "suggested_min", "suggested_max", "gross_bookings", "refunds", "commission", "payouts");
    private static final Set<String> DATES = Set.of("posted_at", "departure_at", "booked_at", "cancelled_at", "first_booking_at",
            "last_booking_at", "last_trip_at", "date");
    private static final Set<String> BOOLEANS = Set.of("paid", "backfilled");

    /** The column's type by its name - names mean the same thing in every report. Unknown names are STRING. */
    public static ReportColumnType of(String column) {
        if (INTEGERS.contains(column)) {
            return INTEGER;
        }
        if (DECIMALS.contains(column)) {
            return DECIMAL;
        }
        if (MONEY_COLUMNS.contains(column)) {
            return MONEY;
        }
        if (DATES.contains(column)) {
            return DATE;
        }
        if (BOOLEANS.contains(column)) {
            return BOOLEAN;
        }
        return STRING;
    }

    /** Every column name the reports use that is meant to be STRING - with the sets above, every column is declared. */
    static final Set<String> STRINGS = Set.of("trip_id", "driver_id", "origin", "destination", "status", "booking_id", "rider_id",
            "kind", "source", "fault", "reason", "corridor", "audience", "stage");

    static boolean declared(String column) {
        return INTEGERS.contains(column) || DECIMALS.contains(column) || MONEY_COLUMNS.contains(column) || DATES.contains(column)
                || BOOLEANS.contains(column) || STRINGS.contains(column);
    }

    public static List<String> labels(List<String> columns) {
        return columns.stream().map(c -> of(c).label()).toList();
    }

    /** The JDBC value as this type's row value: Long, BigDecimal (money: scale 2), String or Boolean; null stays null. */
    public Object convert(Object value) {
        if (value == null) {
            return null;
        }
        return switch (this) {
            case INTEGER -> value instanceof Number n ? (Object) n.longValue() : Long.valueOf(new BigDecimal(value.toString()).longValue());
            case DECIMAL -> {
                BigDecimal d = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
                yield d.stripTrailingZeros().scale() < 0 ? d.setScale(0, RoundingMode.HALF_UP) : d.stripTrailingZeros();
            }
            case MONEY -> (value instanceof BigDecimal b ? b : new BigDecimal(value.toString())).setScale(2, RoundingMode.HALF_UP);
            case BOOLEAN -> value instanceof Boolean b ? b : Boolean.valueOf(value.toString());
            case DATE, STRING -> value.toString();
        };
    }

    /** As a CSV cell: plain numbers (money always 2 dp), text as is. */
    public static String cell(Object converted) {
        if (converted instanceof BigDecimal d) {
            return d.toPlainString();
        }
        return converted == null ? null : converted.toString();
    }

    static Map<String, ReportColumnType> forColumns(List<String> columns) {
        Map<String, ReportColumnType> out = new java.util.LinkedHashMap<>();
        columns.forEach(c -> out.put(c, of(c)));
        return out;
    }
}
