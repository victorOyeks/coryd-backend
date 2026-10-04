package org.coryd.payment.repository;

import org.coryd.payment.entity.EarningsTransaction;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

/** Platform-wide money aggregates for the ops reports, windowed by an explicit [from, to) range. */
public interface PlatformStatsRepository extends Repository<EarningsTransaction, String> {

    interface EarningsSums {
        BigDecimal getGrossFare();
        BigDecimal getCommission();
        BigDecimal getNetCredit();
    }

    interface RefundSums {
        long getCount();
        BigDecimal getAmount();
    }

    @Query(value = """
            select
                coalesce(sum(gross_fare), 0)        as grossFare,
                coalesce(sum(commission_amount), 0) as commission,
                coalesce(sum(net_credit), 0)        as netCredit
            from payment.earnings_transactions
            where created_at >= :from and created_at < :to
            """, nativeQuery = true)
    EarningsSums earnings(@Param("from") Instant from, @Param("to") Instant to);

    /** Refunds are flagged on the hold rather than written as their own row, so updated_at is the refund moment. */
    @Query(value = """
            select
                count(*)                     as count,
                coalesce(sum(amount), 0)     as amount
            from payment.fare_holds
            where refunded = true
              and updated_at >= :from and updated_at < :to
            """, nativeQuery = true)
    RefundSums refunds(@Param("from") Instant from, @Param("to") Instant to);

    /** Point-in-time, not range-bound - what drivers owe back after refunds taken from our balance. */
    @Query(value = "select coalesce(sum(settlement_debt), 0) from payment.driver_earnings_accounts", nativeQuery = true)
    BigDecimal driverDebt();
}
