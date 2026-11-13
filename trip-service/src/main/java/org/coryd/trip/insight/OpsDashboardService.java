package org.coryd.trip.insight;

import lombok.RequiredArgsConstructor;
import org.coryd.trip.config.StuckTripProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dashboard's trip numbers - a handful of aggregate queries per call, never one per row. ops-service caches the
 * composed dashboard for a minute.
 */
@Service
@RequiredArgsConstructor
public class OpsDashboardService {

    static final int FEED_SIZE = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final StuckTripProperties stuckTripProperties;

    @Transactional(readOnly = true)
    public TripDashboard dashboard(LocalDate from, LocalDate to) {
        LagosRange range = LagosRange.of(from, to, 7);
        Instant now = Instant.now();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(range.since()))
                .addValue("to", Timestamp.from(range.until()))
                .addValue("now", Timestamp.from(now))
                .addValue("soonFrom", Timestamp.from(now.minus(Duration.ofHours(2))))
                .addValue("soonTo", Timestamp.from(now.plus(Duration.ofHours(2))));

        Map<String, Object> routes = jdbc.queryForMap("""
                select count(*) as posted,
                       count(*) filter (where status = 'COMPLETED') as completed,
                       count(*) filter (where status = 'CANCELLED') as cancelled,
                       coalesce(sum(seat_capacity), 0) as seats_offered,
                       count(distinct driver_id) as drivers
                from trip.route_posts
                where created_at >= :from and created_at < :to and status <> 'DRAFT'
                """, p);
        Map<String, Object> bookings = jdbc.queryForMap("""
                select count(*) as made,
                       count(*) filter (where status in ('CONFIRMED', 'EN_ROUTE_TO_PICKUP', 'AT_PICKUP', 'IN_PROGRESS')) as underway,
                       count(*) filter (where status = 'COMPLETED') as completed,
                       count(*) filter (where status = 'CANCELLED_RIDER') as cancelled_rider,
                       count(*) filter (where status = 'CANCELLED_DRIVER') as cancelled_driver,
                       count(*) filter (where status = 'NO_SHOW') as no_shows,
                       coalesce(sum(seat_count), 0) as seats,
                       count(distinct rider_id) as riders
                from trip.bookings
                where created_at >= :from and created_at < :to and status not in ('HELD', 'RESERVED')
                """, p);
        long repeatRiders = number(jdbc.queryForObject("""
                select count(*) from (
                    select rider_id from trip.bookings
                    where created_at >= :from and created_at < :to and status not in ('HELD', 'RESERVED')
                    group by rider_id having count(*) >= 2) r
                """, p, Long.class));
        Map<String, Object> completedSeats = jdbc.queryForMap("""
                select count(distinct r.id) as trips, coalesce(sum(b.seat_count), 0) as seats, coalesce(sum(b.fare_total), 0) as gross
                from trip.route_posts r
                left join trip.bookings b on b.route_post_id = r.id and b.status = 'COMPLETED'
                where r.status = 'COMPLETED' and r.created_at >= :from and r.created_at < :to
                """, p);
        Map<String, Object> live = jdbc.queryForMap("""
                select count(*) filter (where status = 'IN_PROGRESS') as in_progress,
                       count(distinct driver_id) filter (where status = 'IN_PROGRESS') as on_trip,
                       count(distinct driver_id) filter (where status = 'IN_PROGRESS'
                           or (status in ('PUBLISHED', 'FULL') and departure_time between :soonFrom and :soonTo)) as online
                from trip.route_posts
                where status in ('IN_PROGRESS', 'PUBLISHED', 'FULL')
                """, p);
        long stuck = jdbc.query("select id, started_at, departure_time, estimated_duration_minutes from trip.route_posts where status = 'IN_PROGRESS'",
                (rs, i) -> {
                    Instant start = rs.getTimestamp(2) != null ? rs.getTimestamp(2).toInstant() : rs.getTimestamp(3).toInstant();
                    int minutes = rs.getObject(4) == null ? org.coryd.trip.service.TripScheduleService.DEFAULT_DURATION_MINUTES : rs.getInt(4);
                    return !now.isBefore(start.plus(Duration.ofMinutes(minutes)).plus(Duration.ofHours(stuckTripProperties.getGraceHours())));
                }).stream().filter(Boolean::booleanValue).count();

        Map<String, Long> bySource = new LinkedHashMap<>();
        Map<String, Long> byKind = new LinkedHashMap<>();
        jdbc.query("""
                select source, kind, count(*) from trip.cancellation_log
                where cancelled_at >= :from and cancelled_at < :to group by source, kind order by source, kind
                """, p, rs -> {
            bySource.merge(rs.getString(1), rs.getLong(3), Long::sum);
            byKind.merge(rs.getString(2), rs.getLong(3), Long::sum);
        });

        long posted = number(routes.get("posted"));
        long completed = number(routes.get("completed"));
        long cancelled = number(routes.get("cancelled"));
        long seatsOffered = number(routes.get("seats_offered"));
        long seatsBooked = number(bookings.get("seats"));
        long completedTrips = number(completedSeats.get("trips"));
        TripDashboard.Trips trips = new TripDashboard.Trips(posted, completed, cancelled, number(live.get("in_progress")), stuck,
                rate(completed, completed + cancelled), rate(cancelled, posted));
        TripDashboard.Seats seats = new TripDashboard.Seats(seatsOffered, seatsBooked, rate(seatsBooked, seatsOffered),
                completedTrips == 0 ? null : round(number(completedSeats.get("seats")) / (double) completedTrips));
        TripDashboard.Drivers drivers = new TripDashboard.Drivers(number(routes.get("drivers")), number(live.get("on_trip")), number(live.get("online")));
        TripDashboard.Riders riders = new TripDashboard.Riders(number(bookings.get("riders")), repeatRiders);
        TripDashboard.Bookings activity = new TripDashboard.Bookings(number(bookings.get("made")), number(bookings.get("underway")),
                number(bookings.get("completed")), number(bookings.get("cancelled_rider")), number(bookings.get("cancelled_driver")),
                number(bookings.get("no_shows")));

        return new TripDashboard(range.from(), range.to(), LagosRange.LAGOS.getId(), trips, seats, drivers, riders, activity, bySource, byKind,
                money(completedSeats.get("gross")), daily(range, p), recentActivity());
    }

    private List<TripDashboard.Day> daily(LagosRange range, MapSqlParameterSource p) {
        Map<LocalDate, long[]> counts = new HashMap<>();
        Map<LocalDate, BigDecimal> gross = new HashMap<>();
        jdbc.query("""
                select (created_at at time zone current_setting('TimeZone') at time zone 'Africa/Lagos')::date as d, count(*),
                       count(*) filter (where status = 'COMPLETED'), count(*) filter (where status = 'CANCELLED')
                from trip.route_posts where created_at >= :from and created_at < :to and status <> 'DRAFT' group by 1
                """, p, rs -> {
            long[] c = counts.computeIfAbsent(rs.getDate(1).toLocalDate(), d -> new long[6]);
            c[0] = rs.getLong(2);
            c[1] = rs.getLong(3);
            c[2] = rs.getLong(4);
        });
        jdbc.query("""
                select (created_at at time zone current_setting('TimeZone') at time zone 'Africa/Lagos')::date as d, count(*), coalesce(sum(seat_count), 0),
                       coalesce(sum(fare_total) filter (where status = 'COMPLETED'), 0)
                from trip.bookings where created_at >= :from and created_at < :to and status not in ('HELD', 'RESERVED') group by 1
                """, p, rs -> {
            LocalDate d = rs.getDate(1).toLocalDate();
            long[] c = counts.computeIfAbsent(d, x -> new long[6]);
            c[3] = rs.getLong(2);
            c[4] = rs.getLong(3);
            gross.put(d, rs.getBigDecimal(4));
        });
        jdbc.query("""
                select (cancelled_at at time zone current_setting('TimeZone') at time zone 'Africa/Lagos')::date as d, count(*)
                from trip.cancellation_log where cancelled_at >= :from and cancelled_at < :to group by 1
                """, p, rs -> {
            counts.computeIfAbsent(rs.getDate(1).toLocalDate(), x -> new long[6])[5] = rs.getLong(2);
        });
        List<TripDashboard.Day> days = new ArrayList<>();
        for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) {
            long[] c = counts.getOrDefault(d, new long[6]);
            days.add(new TripDashboard.Day(d, c[0], c[1], c[2], c[3], c[4], c[5], gross.getOrDefault(d, BigDecimal.ZERO)));
        }
        return days;
    }

    /** Ops actions and platform events, newest first - each source capped, then merged. */
    List<TripDashboard.Activity> recentActivity() {
        MapSqlParameterSource p = new MapSqlParameterSource("n", FEED_SIZE);
        List<TripDashboard.Activity> feed = new ArrayList<>();
        feed.addAll(jdbc.query("""
                select created_at, action, actor_id, actor_name, target_type, target_id, reason
                from trip.ops_audit_log order by created_at desc limit :n
                """, p, (rs, i) -> new TripDashboard.Activity(rs.getTimestamp(1).toInstant(), "OPS_" + rs.getString(2),
                humanize(rs.getString(2)) + ("-".equals(rs.getString(7)) ? "" : ": " + rs.getString(7)),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))));
        feed.addAll(jdbc.query("""
                select created_at, origin_label, destination_label, driver_id, id from trip.route_posts
                where status <> 'DRAFT' order by created_at desc limit :n
                """, p, (rs, i) -> new TripDashboard.Activity(rs.getTimestamp(1).toInstant(), "TRIP_POSTED",
                "Trip posted: " + rs.getString(2) + " to " + rs.getString(3), rs.getString(4), null, "ROUTE", rs.getString(5))));
        feed.addAll(jdbc.query("""
                select created_at, kind, triggered_by, id from trip.sos_incidents order by created_at desc limit :n
                """, p, (rs, i) -> new TripDashboard.Activity(rs.getTimestamp(1).toInstant(),
                "SAFETY_CHECK".equals(rs.getString(2)) ? "SAFETY_CHECK_UNANSWERED" : "SOS_RAISED",
                "SAFETY_CHECK".equals(rs.getString(2)) ? "Safety check went unanswered" : "SOS raised", rs.getString(3), null, "SOS",
                rs.getString(4))));
        feed.addAll(jdbc.query("""
                select cancelled_at, kind, source, actor_id, actor_name, coalesce(booking_id, route_post_id), origin_label, destination_label
                from trip.cancellation_log order by cancelled_at desc limit :n
                """, p, (rs, i) -> new TripDashboard.Activity(rs.getTimestamp(1).toInstant(), "CANCELLATION",
                ("ROUTE".equals(rs.getString(2)) ? "Trip" : "Seat") + " cancelled (" + rs.getString(3).toLowerCase().replace('_', ' ') + ")"
                        + (rs.getString(7) == null ? "" : ": " + rs.getString(7) + " to " + rs.getString(8)),
                rs.getString(4), rs.getString(5), rs.getString(2), rs.getString(6))));
        feed.sort((a, b) -> b.at().compareTo(a.at()));
        return feed.size() > FEED_SIZE ? new ArrayList<>(feed.subList(0, FEED_SIZE)) : feed;
    }

    static String humanize(String action) {
        String s = action.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static Double rate(long part, long whole) {
        return whole == 0 ? null : round(part * 100.0 / whole);
    }

    static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }
}
