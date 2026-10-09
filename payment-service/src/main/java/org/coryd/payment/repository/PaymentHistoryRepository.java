package org.coryd.payment.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.coryd.payment.dto.DriverStatementItem;
import org.coryd.payment.dto.DriverStatementType;
import org.coryd.payment.dto.RefundKind;
import org.coryd.payment.dto.RiderPaymentItem;
import org.coryd.payment.dto.RiderPaymentStatus;
import org.coryd.payment.dto.RiderPaymentType;
import org.coryd.payment.dto.SettlementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Payment histories that span several tables - a native UNION ALL ordered and paged in SQL, so
 * "load more" moves cleanly across every source. id breaks ties: rows can share a timestamp, and an
 * unstable order across a page boundary duplicates and drops rows.
 */
@Repository
public class PaymentHistoryRepository {

    private static final String RIDER_ROWS = """
            select 'PAYMENT' as type, t.id, t.updated_at as occurred_at, t.amount, t.status as status,
                   t.card_type, t.card_last4, null::varchar as refund_kind, t.booking_id
            from payment.payment_transactions t
            where t.rider_id = :userId and t.purpose = 'BOOKING_PAYMENT' and t.status in ('PAID', 'FAILED')
            union all
            select 'REFUND', r.id, coalesce(r.refunded_at, r.created_at), r.amount, r.status,
                   null, null, r.kind, r.booking_id
            from payment.refunds r
            where r.user_id = :userId and r.kind <> 'OVERPAYMENT'
            """;

    private static final String DRIVER_ROWS = """
            select 'TRIP_EARNING' as type, e.id, e.created_at as occurred_at, e.net_credit as amount,
                   e.gross_fare, e.commission_amount, null::numeric as owed_after, null::varchar as payout_status,
                   e.booking_id, null::varchar as reference, null::varchar as note, e.rider_id
            from payment.earnings_transactions e
            where e.driver_id = :userId
            union all
            select 'PAYOUT', s.id, coalesce(s.settlement_date, s.created_at), s.amount,
                   null, null, null, s.status, null, s.gateway_settlement_id, null, null
            from payment.driver_settlements s
            where s.driver_id = :userId
            union all
            select d.type, d.id, d.created_at, d.amount,
                   null, null, d.balance_after, null, d.booking_id, d.reference, d.note, null
            from payment.driver_debt_entries d
            where d.driver_id = :userId
            union all
            select 'OVERPAYMENT_REFUNDED', r.id, coalesce(r.refunded_at, r.created_at), r.amount,
                   null, null, null, null, null, null, r.reason, null
            from payment.refunds r
            where r.user_id = :userId and r.kind = 'OVERPAYMENT'
            """;

    @PersistenceContext
    private EntityManager entityManager;

    public Page<RiderPaymentItem> riderHistory(String riderId, Pageable pageable) {
        List<Object[]> rows = page(RIDER_ROWS, riderId, pageable);
        List<RiderPaymentItem> items = rows.stream().map(PaymentHistoryRepository::toRiderItem).toList();
        return new PageImpl<>(items, pageable, count(RIDER_ROWS, riderId));
    }

    public Page<DriverStatementItem> driverStatement(String driverId, Pageable pageable) {
        List<Object[]> rows = page(DRIVER_ROWS, driverId, pageable);
        List<DriverStatementItem> items = rows.stream().map(PaymentHistoryRepository::toDriverItem).toList();
        return new PageImpl<>(items, pageable, count(DRIVER_ROWS, driverId));
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> page(String rows, String userId, Pageable pageable) {
        Query query = entityManager.createNativeQuery("select * from (" + rows + ") h order by occurred_at desc, id desc limit :limit offset :offset")
                .setParameter("userId", userId)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset());
        return query.getResultList();
    }

    private long count(String rows, String userId) {
        return ((Number) entityManager.createNativeQuery("select count(*) from (" + rows + ") h")
                .setParameter("userId", userId)
                .getSingleResult()).longValue();
    }

    private static RiderPaymentItem toRiderItem(Object[] row) {
        RiderPaymentType type = RiderPaymentType.valueOf((String) row[0]);
        String status = (String) row[4];
        String cardType = (String) row[5];
        String last4 = (String) row[6];
        return new RiderPaymentItem(
                (String) row[1],
                type,
                instant(row[2]),
                (BigDecimal) row[3],
                type == RiderPaymentType.PAYMENT
                        ? ("PAID".equals(status) ? RiderPaymentStatus.PAID : RiderPaymentStatus.FAILED)
                        : ("REFUNDED".equals(status) ? RiderPaymentStatus.REFUNDED : RiderPaymentStatus.REFUND_PROCESSING),
                last4 == null ? null : new RiderPaymentItem.Card(cardType == null ? null : cardType.trim(), last4),
                row[7] == null ? null : RefundKind.valueOf((String) row[7]),
                (String) row[8],
                null);
    }

    private static DriverStatementItem toDriverItem(Object[] row) {
        String riderId = (String) row[11];
        return new DriverStatementItem(
                (String) row[1],
                DriverStatementType.valueOf((String) row[0]),
                instant(row[2]),
                (BigDecimal) row[3],
                (BigDecimal) row[4],
                (BigDecimal) row[5],
                (BigDecimal) row[6],
                row[7] == null ? null : SettlementStatus.fromGateway((String) row[7]),
                (String) row[8],
                (String) row[9],
                (String) row[10],
                null,
                riderId == null ? null : new DriverStatementItem.Rider(riderId, null, null, null));
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return (Instant) value;
    }
}
