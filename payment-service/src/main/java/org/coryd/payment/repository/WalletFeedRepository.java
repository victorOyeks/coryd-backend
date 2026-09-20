package org.coryd.payment.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.payment.dto.WalletTransactionItem;
import org.coryd.payment.dto.WalletTransactionType;
import org.coryd.payment.util.AccountNumberMask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The Wallet screen's mixed Trip/Withdrawal feed - a native UNION ALL across earnings_transactions
 * and payouts, ordered and paginated at the SQL level. Not a JpaRepository since it spans two
 * entities/tables; a Page requires a real count, so fetching each table separately and merging in
 * memory can't paginate correctly - a UNION with LIMIT/OFFSET is what makes "Load more" advance
 * cleanly across both sources.
 *
 * <p>The payouts branch left-joins the payout recipient the withdrawal was actually paid to, so a
 * withdrawal row arrives with its bank details already attached. The trip branch carries rider_id
 * only; the rider's name and photo live in profile-service and are stitched on afterwards, once
 * per page (EarningsService.getTransactionFeed).
 */
@Repository
public class WalletFeedRepository {

    /**
     * The type filter is spliced into the SQL as a literal "and false" per excluded branch rather
     * than bound as a nullable :type parameter - Query.setParameter(name, null) on a *native* (not
     * JPQL) query has no entity metamodel to infer the bind type from, and can throw depending on
     * the Hibernate/driver version. type comes from a validated enum @RequestParam, never raw user
     * text, so splicing it is safe (nothing to inject).
     *
     * <p>id is the ordering tiebreaker, not decoration - occurred_at alone is not unique (the trips
     * on one route settle in the same millisecond batch), and an unstable sort across a LIMIT/OFFSET
     * boundary silently duplicates and drops rows as the client pages.
     */
    private static final String FEED_SQL_TEMPLATE = """
            select * from (
                select 'TRIP' as type, e.id as id, e.created_at as occurred_at,
                       e.booking_id, e.route_post_id, e.gross_fare, e.commission_amount, e.net_credit,
                       e.rider_id,
                       null::numeric as amount, null::varchar as payout_status,
                       null::varchar as failure_reason, null::varchar as bank_name,
                       null::varchar as account_name, null::varchar as account_number
                from payment.earnings_transactions e
                where e.driver_id = :driverId and e.created_at >= :since and e.created_at < :until
                  %s

                union all

                select 'WITHDRAWAL' as type, p.id as id, p.created_at as occurred_at,
                       null::varchar as booking_id, null::varchar as route_post_id,
                       null::numeric as gross_fare, null::numeric as commission_amount,
                       null::numeric as net_credit, null::varchar as rider_id,
                       p.amount, p.status as payout_status,
                       p.failure_reason, r.bank_name,
                       r.account_name, r.account_number
                from payment.payouts p
                left join payment.payout_recipients r on r.id = p.payout_recipient_id
                where p.driver_id = :driverId and p.created_at >= :since and p.created_at < :until
                  %s
            ) feed
            order by occurred_at desc, id desc
            limit :limit offset :offset
            """;

    private static final String COUNT_SQL_TEMPLATE = """
            select count(*) from (
                select id from payment.earnings_transactions
                where driver_id = :driverId and created_at >= :since and created_at < :until
                  %s

                union all

                select id from payment.payouts
                where driver_id = :driverId and created_at >= :since and created_at < :until
                  %s
            ) feed
            """;

    @PersistenceContext
    private EntityManager entityManager;

    public Page<WalletTransactionItem> findFeed(String driverId, WalletTransactionType type,
                                                 Instant since, Instant until, Pageable pageable) {
        String tripFilter = (type == null || type == WalletTransactionType.TRIP) ? "" : "and false";
        String withdrawalFilter = (type == null || type == WalletTransactionType.WITHDRAWAL) ? "" : "and false";

        Query feedQuery = entityManager.createNativeQuery(FEED_SQL_TEMPLATE.formatted(tripFilter, withdrawalFilter))
                .setParameter("driverId", driverId)
                .setParameter("since", since)
                .setParameter("until", until)
                .setParameter("limit", pageable.getPageSize())
                .setParameter("offset", pageable.getOffset());

        Query countQuery = entityManager.createNativeQuery(COUNT_SQL_TEMPLATE.formatted(tripFilter, withdrawalFilter))
                .setParameter("driverId", driverId)
                .setParameter("since", since)
                .setParameter("until", until);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = feedQuery.getResultList();
        long total = ((Number) countQuery.getSingleResult()).longValue();

        List<WalletTransactionItem> items = rows.stream().map(this::toItem).toList();
        return new PageImpl<>(items, pageable, total);
    }

    private WalletTransactionItem toItem(Object[] row) {
        String riderId = (String) row[8];
        String bankName = (String) row[12];
        String accountName = (String) row[13];
        String accountNumber = (String) row[14];

        return new WalletTransactionItem(
                WalletTransactionType.valueOf((String) row[0]),
                (String) row[1],
                toInstant(row[2]),
                (String) row[3],
                (String) row[4],
                (BigDecimal) row[5],
                (BigDecimal) row[6],
                (BigDecimal) row[7],
                // Identity only at this layer - the name/photo are filled in per page, not per row.
                riderId == null ? null : new WalletTransactionItem.Rider(riderId, null, null, null),
                (BigDecimal) row[9],
                row[10] == null ? null : PayoutStatus.valueOf((String) row[10]),
                (String) row[11],
                bankName == null ? null : new WalletTransactionItem.BankAccount(
                        bankName, accountName, AccountNumberMask.mask(accountNumber))
        );
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return (Instant) value;
    }
}
