package org.coryd.trip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.coryd.common.exception.BadRequestException;
import org.coryd.trip.dto.OpsPricingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ops Console pricing monitor - read-only. Fares are grouped by the same corridor key RouteService gives every posted fare
 * (normalised labels, "origin->destination"), compared with the suggested range SuggestedFareJob keeps for it.
 */
@Service
public class OpsPricingService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final int MAX_DAYS = 366;
    private static final int MAX_ROWS = 200;

    /** RouteService.corridorKey in SQL: trimmed, runs of whitespace to one space, lower-case, joined by "->". */
    private static final String CORRIDOR = "lower(regexp_replace(trim(%1$s.origin_label), '\\s+', ' ', 'g')) || '->' || "
            + "lower(regexp_replace(trim(%1$s.destination_label), '\\s+', ' ', 'g'))";

    private static final String ROUTES = """
            select r.id, r.driver_id, r.origin_label, r.destination_label, r.departure_time, r.status, r.fare_per_seat,
                   %s as corridor_key
            from trip.route_posts r
            where r.departure_time >= :from and r.departure_time < :to and r.status <> 'DRAFT'
            """.formatted(CORRIDOR.formatted("r"));

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public OpsPricingResponse pricing(LocalDate from, LocalDate to, String corridor, boolean outliersOnly) {
        LocalDate end = to == null ? LocalDate.now(LAGOS) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw new BadRequestException("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new BadRequestException("The window can be at most " + MAX_DAYS + " days");
        }
        Instant since = start.atStartOfDay(LAGOS).toInstant();
        Instant until = end.plusDays(1).atStartOfDay(LAGOS).toInstant();
        String match = corridor == null || corridor.isBlank() ? null : "%" + corridor.trim().toLowerCase(Locale.ROOT) + "%";
        String corridorFilter = match == null ? "" : " where x.corridor_key like :match";

        List<OpsPricingResponse.Corridor> corridors = new ArrayList<>();
        for (Object[] r : rows("""
                with x as (%s), rep as (
                    select %s as corridor_key, count(*) as n
                    from trip.issue_reports i
                    join trip.bookings b on b.id = i.booking_id
                    join trip.route_posts r on r.id = b.route_post_id
                    where i.category = 'PAYMENT' and r.departure_time >= :from and r.departure_time < :to and r.status <> 'DRAFT'
                    group by 1
                )
                select x.corridor_key, min(x.origin_label), min(x.destination_label), count(*),
                       min(x.fare_per_seat), percentile_cont(0.5) within group (order by x.fare_per_seat), avg(x.fare_per_seat),
                       max(x.fare_per_seat), s.min_fare, s.max_fare, s.sample_size,
                       sum(case when s.id is not null and (x.fare_per_seat < s.min_fare or x.fare_per_seat > s.max_fare) then 1 else 0 end),
                       coalesce(max(rep.n), 0)
                from x
                left join trip.suggested_fare_ranges s on s.corridor_key = x.corridor_key
                left join rep on rep.corridor_key = x.corridor_key
                %s
                group by x.corridor_key, s.id, s.min_fare, s.max_fare, s.sample_size
                order by count(*) desc, x.corridor_key
                """.formatted(ROUTES, CORRIDOR.formatted("r"), corridorFilter), since, until, match)) {
            long outliers = ((Number) r[11]).longValue();
            if (outliersOnly && outliers == 0) {
                continue;
            }
            corridors.add(new OpsPricingResponse.Corridor((String) r[0], (String) r[1], (String) r[2], ((Number) r[3]).longValue(),
                    money(r[4]), money(r[5]), money(r[6]), money(r[7]), money(r[8]), money(r[9]),
                    r[10] == null ? null : ((Number) r[10]).intValue(), outliers, ((Number) r[12]).longValue()));
        }

        List<OpsPricingResponse.Outlier> outliers = new ArrayList<>();
        for (Object[] r : rows("""
                select x.id, x.driver_id, x.corridor_key, x.origin_label, x.destination_label, x.departure_time, x.status,
                       x.fare_per_seat, s.min_fare, s.max_fare
                from (%s) x
                join trip.suggested_fare_ranges s on s.corridor_key = x.corridor_key
                where (x.fare_per_seat < s.min_fare or x.fare_per_seat > s.max_fare)%s
                order by greatest((x.fare_per_seat - s.max_fare) / nullif(s.max_fare, 0), (s.min_fare - x.fare_per_seat) / nullif(s.min_fare, 0)) desc,
                         x.departure_time desc
                limit %d
                """.formatted(ROUTES, match == null ? "" : " and x.corridor_key like :match", MAX_ROWS), since, until, match)) {
            BigDecimal fare = money(r[7]);
            BigDecimal min = money(r[8]);
            BigDecimal max = money(r[9]);
            boolean above = fare.compareTo(max) > 0;
            BigDecimal bound = above ? max : min;
            BigDecimal deviation = bound.signum() == 0 ? null
                    : fare.subtract(bound).abs().multiply(BigDecimal.valueOf(100)).divide(bound, 1, RoundingMode.HALF_UP);
            outliers.add(new OpsPricingResponse.Outlier((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    instant(r[5]), (String) r[6], fare, min, max, above ? "ABOVE" : "BELOW", deviation));
        }

        List<OpsPricingResponse.RiderReport> reports = new ArrayList<>();
        for (Object[] r : rows("""
                select i.id, i.reference_number, i.booking_id, r.id, i.reported_by, %s, i.description, i.status, i.created_at
                from trip.issue_reports i
                join trip.bookings b on b.id = i.booking_id
                join trip.route_posts r on r.id = b.route_post_id
                where i.category = 'PAYMENT' and r.departure_time >= :from and r.departure_time < :to and r.status <> 'DRAFT'%s
                order by i.created_at desc
                limit %d
                """.formatted(CORRIDOR.formatted("r"), match == null ? "" : " and " + CORRIDOR.formatted("r") + " like :match", MAX_ROWS),
                since, until, match)) {
            reports.add(new OpsPricingResponse.RiderReport((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    (String) r[5], (String) r[6], (String) r[7], instant(r[8])));
        }

        long routes = corridors.stream().mapToLong(OpsPricingResponse.Corridor::routes).sum();
        long withRange = corridors.stream().filter(c -> c.suggestedMin() != null).count();
        long outlierCount = corridors.stream().mapToLong(OpsPricingResponse.Corridor::outliers).sum();
        long reportCount = corridors.stream().mapToLong(OpsPricingResponse.Corridor::riderFareReports).sum();
        return new OpsPricingResponse(start, end, new OpsPricingResponse.Totals(routes, corridors.size(), withRange, outlierCount, reportCount),
                corridors, outliers, reports);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Instant from, Instant to, String match) {
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("from", from);
        query.setParameter("to", to);
        if (match != null) {
            query.setParameter("match", match);
        }
        return query.getResultList();
    }

    private static BigDecimal money(Object value) {
        if (value == null) {
            return null;
        }
        BigDecimal number = value instanceof BigDecimal b ? b : BigDecimal.valueOf(((Number) value).doubleValue());
        return number.setScale(2, RoundingMode.HALF_UP);
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof java.time.LocalDateTime l) {
            return Timestamp.valueOf(l).toInstant();
        }
        return (Instant) value;
    }
}
