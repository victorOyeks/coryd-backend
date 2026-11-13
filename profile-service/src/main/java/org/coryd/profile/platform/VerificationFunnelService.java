package org.coryd.profile.platform;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * The driver and rider verification funnels for people who joined in [from, to] (Lagos days), and the point-in-time
 * pipeline the dashboard shows - all from the directory indexes, a few aggregate queries.
 */
@Service
@RequiredArgsConstructor
public class VerificationFunnelService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final NamedParameterJdbcTemplate jdbc;

    public record Stage(String audience, String stage, long people, Double percentOfJoined) {
    }

    public record Funnel(LocalDate from, LocalDate to, String timezone, List<Stage> stages) {
    }

    /** Right now, whoever joined when. drivers.verified: overall APPROVED; queue: waiting on ops by tab. */
    public record Pipeline(long driversTotal, long driversVerified, long driversSuspended, long driversActiveLast7Days,
                           Map<String, Long> driverQueue, long ridersTotal, long ridersSuspended, long ridersActiveLast7Days,
                           Map<String, Long> riderIdentity) {
    }

    public Funnel funnel(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(LAGOS) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) + 1 > 366) {
            throw new BadRequestException("from must be on or before to, and the range at most 366 days");
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(start.atStartOfDay(LAGOS).toInstant()))
                .addValue("to", Timestamp.from(end.plusDays(1).atStartOfDay(LAGOS).toInstant()));
        Map<String, Object> d = jdbc.queryForMap("""
                select count(*) as joined,
                       count(*) filter (where submitted_at is not null or overall_status in ('IN_REVIEW', 'APPROVED', 'REJECTED')) as submitted,
                       count(*) filter (where overall_status = 'APPROVED') as approved,
                       count(*) filter (where overall_status = 'REJECTED') as rejected,
                       count(*) filter (where last_trip_at is not null) as drove
                from profile.driver_index where joined_at >= :from and joined_at < :to
                """, p);
        Map<String, Object> r = jdbc.queryForMap("""
                select count(*) as joined,
                       count(*) filter (where identity_status <> 'NOT_STARTED') as started,
                       count(*) filter (where identity_status = 'APPROVED') as approved,
                       count(*) filter (where identity_status in ('FAILED', 'LOCKED_OUT')) as failed,
                       count(*) filter (where last_trip_at is not null) as rode
                from profile.rider_index where joined_at >= :from and joined_at < :to
                """, p);
        long dj = n(d.get("joined"));
        long rj = n(r.get("joined"));
        return new Funnel(start, end, LAGOS.getId(), List.of(
                stage("DRIVERS", "JOINED", dj, dj), stage("DRIVERS", "SUBMITTED_DOCUMENTS", n(d.get("submitted")), dj),
                stage("DRIVERS", "APPROVED", n(d.get("approved")), dj), stage("DRIVERS", "REJECTED", n(d.get("rejected")), dj),
                stage("DRIVERS", "COMPLETED_A_TRIP", n(d.get("drove")), dj),
                stage("RIDERS", "JOINED", rj, rj), stage("RIDERS", "STARTED_ID_CHECK", n(r.get("started")), rj),
                stage("RIDERS", "ID_APPROVED", n(r.get("approved")), rj), stage("RIDERS", "ID_FAILED_OR_LOCKED", n(r.get("failed")), rj),
                stage("RIDERS", "COMPLETED_A_TRIP", n(r.get("rode")), rj)));
    }

    public Pipeline pipeline() {
        MapSqlParameterSource p = new MapSqlParameterSource("week", Timestamp.from(java.time.Instant.now().minus(7, ChronoUnit.DAYS)));
        Map<String, Object> d = jdbc.queryForMap("""
                select count(*) as total, count(*) filter (where overall_status = 'APPROVED') as verified,
                       count(*) filter (where directory_status = 'SUSPENDED') as suspended,
                       count(*) filter (where last_trip_at >= :week) as active
                from profile.driver_index
                """, p);
        Map<String, Object> r = jdbc.queryForMap("""
                select count(*) as total, count(*) filter (where directory_status = 'SUSPENDED') as suspended,
                       count(*) filter (where last_trip_at >= :week) as active
                from profile.rider_index
                """, p);
        Map<String, Long> queue = new java.util.LinkedHashMap<>();
        jdbc.query("select queue_tab, count(*) from profile.driver_index where queue_tab is not null group by queue_tab order by 1", p,
                rs -> {
                    queue.put(rs.getString(1), rs.getLong(2));
                });
        Map<String, Long> identity = new java.util.LinkedHashMap<>();
        jdbc.query("select identity_status, count(*) from profile.rider_index group by identity_status order by 1", p,
                rs -> {
                    identity.put(rs.getString(1), rs.getLong(2));
                });
        return new Pipeline(n(d.get("total")), n(d.get("verified")), n(d.get("suspended")), n(d.get("active")), queue,
                n(r.get("total")), n(r.get("suspended")), n(r.get("active")), identity);
    }

    private static Stage stage(String audience, String stage, long people, long joined) {
        return new Stage(audience, stage, people, joined == 0 ? null : Math.round(people * 1000.0 / joined) / 10.0);
    }

    private static long n(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
