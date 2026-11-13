package org.coryd.payment.ops.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.coryd.payment.ops.dto.TransactionFilter;
import org.coryd.payment.ops.dto.TransactionRow;
import org.coryd.payment.ops.dto.TransactionType;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Ops Console's money reads that span tables: one Transactions list across payments, refunds and payouts (a native
 * UNION ALL, filtered, sorted and paged in SQL), and the finance dashboard's sums and daily series. Times are bound as
 * Instants so Hibernate writes and reads them the same way; days are Lagos days, worked out in SQL from the session's own
 * time zone, so the result doesn't depend on the server's.
 */
@Repository
public class OpsMoneyQueryRepository {

    /** "B-524EA3" - the console's short reference: the first six characters of the id, upper-case. */
    private static final Pattern SHORT_REF = Pattern.compile("^[A-Za-z]-[0-9A-Fa-f]{6}$");

    static final String LAGOS_DAY = "cast((%s at time zone current_setting('TimeZone')) at time zone 'Africa/Lagos' as date)";

    private static final String ROWS = """
            select 'PAYMENT' as type, t.id, t.created_at as occurred_at, t.status, t.amount, t.gateway_reference as reference,
                   t.booking_id,
                   case when t.purpose = 'DEBT_REPAYMENT' then null else t.rider_id end as rider_id,
                   case when t.purpose = 'DEBT_REPAYMENT' then t.rider_id else h.driver_id end as driver_id,
                   t.purpose as kind, cast(null as varchar) as reason, t.card_type, t.card_last4
            from payment.payment_transactions t
            left join payment.fare_holds h on h.booking_id = t.booking_id
            union all
            select 'REFUND', r.id, coalesce(r.refunded_at, r.created_at), r.status, r.amount, t.gateway_reference,
                   r.booking_id,
                   case when r.kind = 'OVERPAYMENT' then null else r.user_id end,
                   case when r.kind = 'OVERPAYMENT' then r.user_id else h.driver_id end,
                   case when q.id is not null then 'MANUAL' else r.kind end, r.reason, t.card_type, t.card_last4
            from payment.refunds r
            left join payment.payment_transactions t on t.id = r.payment_transaction_id
            left join payment.fare_holds h on h.booking_id = r.booking_id
            left join payment.ops_money_requests q on q.refund_id = r.id
            union all
            select 'PAYOUT', s.id, coalesce(s.settlement_date, s.created_at),
                   case lower(s.status) when 'success' then 'PAID' else upper(s.status) end,
                   s.amount, s.gateway_settlement_id, cast(null as varchar), cast(null as varchar), s.driver_id,
                   cast(null as varchar), cast(null as varchar), cast(null as varchar), cast(null as varchar)
            from payment.driver_settlements s
            """;

    @PersistenceContext
    private EntityManager entityManager;

    // ---- Transactions list ---------------------------------------------------------------------------------------

    public List<TransactionRow> transactions(TransactionFilter filter, int page, int size) {
        Where where = where(filter, true);
        String order = switch (filter.sort() == null ? TransactionFilter.Sort.NEWEST : filter.sort()) {
            case NEWEST -> "occurred_at desc, id desc";
            case OLDEST -> "occurred_at asc, id asc";
            case AMOUNT_DESC -> "amount desc, occurred_at desc, id desc";
            case AMOUNT_ASC -> "amount asc, occurred_at desc, id desc";
        };
        Query query = entityManager.createNativeQuery("select * from (" + ROWS + ") x" + where.sql() + " order by " + order
                + " limit :limit offset :offset");
        where.bind(query);
        query.setParameter("limit", size);
        query.setParameter("offset", (long) page * size);
        List<TransactionRow> rows = new ArrayList<>();
        for (Object o : query.getResultList()) {
            rows.add(row((Object[]) o));
        }
        return rows;
    }

    private static TransactionRow row(Object[] r) {
        return new TransactionRow(TransactionType.valueOf((String) r[0]), (String) r[1], instant(r[2]), (String) r[3],
                (BigDecimal) r[4], (String) r[5], (String) r[6], (String) r[7], (String) r[8], (String) r[9], (String) r[10],
                r[11] == null ? null : ((String) r[11]).trim(), (String) r[12]);
    }

    public java.util.Optional<TransactionRow> transaction(TransactionType type, String id) {
        Query query = entityManager.createNativeQuery("select * from (" + ROWS + ") x where type = :type and id = :id");
        query.setParameter("type", type.name());
        query.setParameter("id", id);
        List<?> result = query.getResultList();
        return result.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(row((Object[]) result.get(0)));
    }

    public long countTransactions(TransactionFilter filter) {
        Where where = where(filter, true);
        Query query = entityManager.createNativeQuery("select count(*) from (" + ROWS + ") x" + where.sql());
        where.bind(query);
        return ((Number) query.getSingleResult()).longValue();
    }

    /** The tab badges: every filter but the type. */
    public Map<TransactionType, Long> countByType(TransactionFilter filter) {
        Where where = where(filter, false);
        Query query = entityManager.createNativeQuery("select type, count(*) from (" + ROWS + ") x" + where.sql() + " group by type");
        where.bind(query);
        Map<TransactionType, Long> counts = new EnumMap<>(TransactionType.class);
        for (TransactionType type : TransactionType.values()) {
            counts.put(type, 0L);
        }
        for (Object o : query.getResultList()) {
            Object[] r = (Object[]) o;
            counts.put(TransactionType.valueOf((String) r[0]), ((Number) r[1]).longValue());
        }
        return counts;
    }

    /** Payout status tabs: every filter but the status. */
    public Map<String, Long> payoutCountsByStatus(TransactionFilter filter) {
        TransactionFilter payouts = filter.withType(TransactionType.PAYOUT).withStatus(null);
        Where where = where(payouts, true);
        Query query = entityManager.createNativeQuery("select status, count(*) from (" + ROWS + ") x" + where.sql() + " group by status");
        where.bind(query);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "PROCESSING", "PAID", "FAILED")) {
            counts.put(status, 0L);
        }
        for (Object o : query.getResultList()) {
            Object[] r = (Object[]) o;
            counts.merge((String) r[0], ((Number) r[1]).longValue(), Long::sum);
        }
        return counts;
    }

    private Where where(TransactionFilter f, boolean withType) {
        Where w = new Where();
        if (withType && f.type() != null) {
            w.add("type = :type", "type", f.type().name());
        }
        if (f.status() != null && !f.status().isBlank()) {
            w.add("status = :status", "status", f.status().trim().toUpperCase(Locale.ROOT));
        }
        if (f.from() != null) {
            w.add("occurred_at >= :from", "from", f.from());
        }
        if (f.to() != null) {
            w.add("occurred_at < :to", "to", f.to());
        }
        idOrRef(w, "rider_id", "riderId", f.riderId());
        idOrRef(w, "driver_id", "driverId", f.driverId());
        idOrRef(w, "booking_id", "bookingId", f.bookingId());
        if (f.bookingIds() != null) {
            if (f.bookingIds().isEmpty()) {
                w.clauses.add("1 = 0");
            } else {
                w.add("booking_id in (:bookingIds)", "bookingIds", f.bookingIds());
            }
        }
        if (f.amountMin() != null) {
            w.add("amount >= :amountMin", "amountMin", f.amountMin());
        }
        if (f.amountMax() != null) {
            w.add("amount <= :amountMax", "amountMax", f.amountMax());
        }
        if (f.reference() != null && !f.reference().isBlank()) {
            w.add("lower(reference) like :reference", "reference", "%" + f.reference().trim().toLowerCase(Locale.ROOT) + "%");
        }
        return w;
    }

    /** A full id matches exactly; a short reference ("R-A11CE0") matches the ids it was made from. */
    private static void idOrRef(Where w, String column, String param, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String v = value.trim();
        if (SHORT_REF.matcher(v).matches()) {
            w.add("lower(" + column + ") like :" + param, param, v.substring(2).toLowerCase(Locale.ROOT) + "%");
        } else {
            w.add(column + " = :" + param, param, v);
        }
    }

    private static final class Where {
        final List<String> clauses = new ArrayList<>();
        final Map<String, Object> params = new LinkedHashMap<>();

        void add(String clause, String name, Object value) {
            clauses.add(clause);
            params.put(name, value);
        }

        String sql() {
            return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
        }

        void bind(Query query) {
            params.forEach(query::setParameter);
        }
    }

    // ---- refunds stuck without an automatic retry ----------------------------------------------------------------

    /** PENDING refunds older than the cutoff whose hold isn't on the automatic retry - nothing will move them on its own. */
    @SuppressWarnings("unchecked")
    public List<Object[]> stuckPendingRefunds(Instant olderThan) {
        return entityManager.createNativeQuery("""
                        select r.id, r.booking_id, r.user_id, h.driver_id, r.amount, r.reason, r.created_at
                        from payment.refunds r
                        left join payment.fare_holds h on h.booking_id = r.booking_id
                        where r.status = 'PENDING' and r.created_at < :cutoff and coalesce(h.refund_due, 0) = 0
                        order by r.created_at desc
                        """)
                .setParameter("cutoff", olderThan)
                .getResultList();
    }

    // ---- finance dashboard ---------------------------------------------------------------------------------------

    public record Sum(long count, BigDecimal amount) {
        static Sum of(Object result) {
            Object[] r = (Object[]) result;
            return new Sum(((Number) r[0]).longValue(), r[1] == null ? BigDecimal.ZERO : (BigDecimal) r[1]);
        }
    }

    public Sum sum(String sql, Instant from, Instant to) {
        return sum(sql, from, to, Map.of());
    }

    public Sum sum(String sql, Instant from, Instant to, Map<String, Object> params) {
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
        return Sum.of(query.getSingleResult());
    }

    /** A daily series: sql selects (lagos day, amount) grouped by day, for [from, to). */
    public Map<LocalDate, Sum> daily(String table, String timeColumn, String amountColumn, String condition, Instant from, Instant to) {
        String day = LAGOS_DAY.formatted(timeColumn);
        Query query = entityManager.createNativeQuery("select " + day + " as d, count(*), coalesce(sum(" + amountColumn + "), 0) from "
                + table + " where " + timeColumn + " >= :from and " + timeColumn + " < :to"
                + (condition == null ? "" : " and " + condition) + " group by d");
        query.setParameter("from", from);
        query.setParameter("to", to);
        Map<LocalDate, Sum> byDay = new LinkedHashMap<>();
        for (Object o : query.getResultList()) {
            Object[] r = (Object[]) o;
            byDay.put(localDate(r[0]), new Sum(((Number) r[1]).longValue(), (BigDecimal) r[2]));
        }
        return byDay;
    }

    public static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return Timestamp.valueOf(ldt).toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return (Instant) value;
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        return (LocalDate) value;
    }
}
