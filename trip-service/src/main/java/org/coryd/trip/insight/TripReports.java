package org.coryd.trip.insight;

import org.coryd.common.exception.ResourceNotFoundException;

import java.util.Arrays;
import java.util.List;

/**
 * The reports trip-service owns (docs/ops-console/dashboard-reports.md). Each is one SQL query over [:from, :to) with its
 * column headings; times are Lagos local time. Drivers and riders are by id - ops-service adds names to the JSON preview.
 * Per-trip booking sums come from one grouped join, never a subquery per trip: over six months that was hundreds of
 * thousands of index lookups and seconds per report.
 */
public enum TripReports {

    TRIPS("Trips", "Every trip posted in the range: route, departure, status, seats, fare and what it earned.",
            List.of("trip_id", "posted_at", "departure_at", "driver_id", "origin", "destination", "status", "seat_capacity",
                    "seats_booked", "fare_per_seat", "gross_completed"),
            """
            select r.id, %1$s, %2$s, r.driver_id, r.origin_label, r.destination_label, r.status, r.seat_capacity,
                   coalesce(sum(b.seat_count) filter (where b.status not in ('HELD', 'RESERVED', 'CANCELLED_RIDER', 'CANCELLED_DRIVER')), 0),
                   r.fare_per_seat, coalesce(sum(b.fare_total) filter (where b.status = 'COMPLETED'), 0)
            from trip.route_posts r left join trip.bookings b on b.route_post_id = r.id
            where r.created_at >= :from and r.created_at < :to and r.status <> 'DRAFT'
            group by r.id order by r.created_at
            """.formatted(LagosRangeSql.local("r.created_at"), LagosRangeSql.local("r.departure_time")), false),
    BOOKINGS("Bookings", "Every booking made in the range (payment-pending holds left out), with its trip and outcome.",
            List.of("booking_id", "booked_at", "trip_id", "departure_at", "rider_id", "driver_id", "status", "seats", "fare_total",
                    "origin", "destination"),
            """
            select b.id, %1$s, r.id, %2$s, b.rider_id, r.driver_id, b.status, b.seat_count, b.fare_total, r.origin_label, r.destination_label
            from trip.bookings b join trip.route_posts r on r.id = b.route_post_id
            where b.created_at >= :from and b.created_at < :to and b.status not in ('HELD', 'RESERVED')
            order by b.created_at
            """.formatted(LagosRangeSql.local("b.created_at"), LagosRangeSql.local("r.departure_time")), true),
    CANCELLATIONS("Cancellations", "Every trip and seat cancelled in the range: who, when, how close to departure, fee and refund.",
            List.of("cancelled_at", "kind", "source", "fault", "trip_id", "booking_id", "rider_id", "driver_id", "minutes_before_departure",
                    "seats", "fare_total", "paid", "fee", "refund_expected", "reason", "backfilled"),
            """
            select %1$s, c.kind, c.source, c.fault, c.route_post_id, c.booking_id, c.rider_id, c.driver_id, c.minutes_before_departure,
                   c.seat_count, c.fare_total, c.paid, c.fee_amount, c.refund_expected, c.reason, c.backfilled
            from trip.cancellation_log c
            where c.cancelled_at >= :from and c.cancelled_at < :to
            order by c.cancelled_at
            """.formatted(LagosRangeSql.local("c.cancelled_at")), true),
    DRIVER_PERFORMANCE("Driver performance", "Per driver, for trips posted in the range: trips, completion, seats filled, fares earned and rating.",
            List.of("driver_id", "trips_posted", "trips_completed", "trips_cancelled", "completion_rate_pct", "seats_offered", "seats_booked",
                    "fill_rate_pct", "gross_completed", "avg_rating", "ratings"),
            """
            with t as (
                select r.driver_id, r.id, r.status, r.seat_capacity,
                       coalesce(sum(b.seat_count) filter (where b.status not in ('HELD', 'RESERVED', 'CANCELLED_RIDER', 'CANCELLED_DRIVER')), 0) as booked,
                       coalesce(sum(b.fare_total) filter (where b.status = 'COMPLETED'), 0) as gross
                from trip.route_posts r left join trip.bookings b on b.route_post_id = r.id
                where r.created_at >= :from and r.created_at < :to and r.status <> 'DRAFT'
                group by r.id
            ), rt as (
                select ratee_id, round(avg(stars)::numeric, 2) as avg_stars, count(*) as n from trip.ratings
                where direction = 'RIDER_TO_DRIVER' and hidden_at is null and created_at >= :from and created_at < :to group by ratee_id
            )
            select t.driver_id, count(*), count(*) filter (where t.status = 'COMPLETED'), count(*) filter (where t.status = 'CANCELLED'),
                   round(100.0 * count(*) filter (where t.status = 'COMPLETED')
                         / nullif(count(*) filter (where t.status in ('COMPLETED', 'CANCELLED')), 0), 1),
                   sum(t.seat_capacity), sum(t.booked), round(100.0 * sum(t.booked) / nullif(sum(t.seat_capacity), 0), 1), sum(t.gross),
                   max(rt.avg_stars), coalesce(max(rt.n), 0)
            from t left join rt on rt.ratee_id = t.driver_id
            group by t.driver_id order by count(*) desc, t.driver_id
            """, false),
    RIDERS("Riders", "Per rider, for bookings made in the range: bookings, completed rides, cancellations, no-shows and spend.",
            List.of("rider_id", "bookings", "completed", "cancelled_by_rider", "cancelled_by_driver", "no_shows", "seats", "spent_completed",
                    "first_booking_at", "last_booking_at"),
            """
            select b.rider_id, count(*), count(*) filter (where b.status = 'COMPLETED'), count(*) filter (where b.status = 'CANCELLED_RIDER'),
                   count(*) filter (where b.status = 'CANCELLED_DRIVER'), count(*) filter (where b.status = 'NO_SHOW'), sum(b.seat_count),
                   coalesce(sum(b.fare_total) filter (where b.status = 'COMPLETED'), 0), %1$s, %2$s
            from trip.bookings b
            where b.created_at >= :from and b.created_at < :to and b.status not in ('HELD', 'RESERVED')
            group by b.rider_id order by count(*) desc, b.rider_id
            """.formatted(LagosRangeSql.local("min(b.created_at)"), LagosRangeSql.local("max(b.created_at)")), false),
    CORRIDORS("Corridors", "Every origin -> destination pair trips were posted on in the range, with volumes and the suggested fare range.",
            List.of("corridor", "origin", "destination", "trips", "drivers", "seats_offered", "seats_booked", "median_fare", "suggested_min",
                    "suggested_max", "suggested_sample", "last_trip_at"),
            """
            with x as (
                select r.id, r.driver_id, r.origin_label, r.destination_label, r.seat_capacity, r.fare_per_seat, r.departure_time,
                       %1$s as corridor_key,
                       coalesce(sum(b.seat_count) filter (where b.status not in ('HELD', 'RESERVED', 'CANCELLED_RIDER', 'CANCELLED_DRIVER')), 0) as booked
                from trip.route_posts r left join trip.bookings b on b.route_post_id = r.id
                where r.created_at >= :from and r.created_at < :to and r.status <> 'DRAFT'
                group by r.id
            )
            select x.corridor_key, min(x.origin_label), min(x.destination_label), count(*), count(distinct x.driver_id), sum(x.seat_capacity),
                   sum(x.booked), percentile_cont(0.5) within group (order by x.fare_per_seat), s.min_fare, s.max_fare, s.sample_size,
                   %2$s
            from x left join trip.suggested_fare_ranges s on s.corridor_key = x.corridor_key
            group by x.corridor_key, s.min_fare, s.max_fare, s.sample_size order by count(*) desc, x.corridor_key
            """.formatted(LagosRangeSql.CORRIDOR, LagosRangeSql.local("max(x.departure_time)")), false);

    private final String title;
    private final String description;
    private final List<String> columns;
    private final String sql;
    private final boolean inIndexOrder;

    TripReports(String title, String description, List<String> columns, String sql, boolean inIndexOrder) {
        this.title = title;
        this.description = description;
        this.columns = columns;
        this.sql = sql;
        this.inIndexOrder = inIndexOrder;
    }

    /**
     * True when the rows come straight off an index in report order (one row per booking or cancellation), so a limited read
     * stops early and a separate count is cheap - better than one run that has to finish the whole result to count it.
     * False for the reports that group and sort everything before the first row: those count in the same run
     * (OpsReportService), rather than doing all that work twice.
     */
    public boolean inIndexOrder() {
        return inIndexOrder;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<String> columns() {
        return columns;
    }

    public String sql() {
        return sql;
    }

    /** trips, bookings, cancellations, driver-performance, riders, corridors. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }

    public static TripReports bySlug(String slug) {
        return Arrays.stream(values()).filter(r -> r.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No report called '" + slug + "'"));
    }

    static final class LagosRangeSql {
        /** RouteService.corridorKey in SQL - the same as the pricing monitor's. */
        static final String CORRIDOR = "lower(regexp_replace(trim(r.origin_label), '\\s+', ' ', 'g')) || '->' || "
                + "lower(regexp_replace(trim(r.destination_label), '\\s+', ' ', 'g'))";

        static String local(String column) {
            return "to_char((" + column + " at time zone current_setting('TimeZone')) at time zone 'Africa/Lagos', 'YYYY-MM-DD HH24:MI')";
        }

        private LagosRangeSql() {
        }
    }
}
