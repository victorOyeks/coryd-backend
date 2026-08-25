package org.coryd.payment.repository;

import org.coryd.payment.entity.EarningsTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface EarningsTransactionRepository extends JpaRepository<EarningsTransaction, String> {
    List<EarningsTransaction> findByDriverId(String driverId);
    List<EarningsTransaction> findByBookingIdIn(List<String> bookingIds);

    @Query("select coalesce(sum(e.netCredit), 0) from EarningsTransaction e where e.driverId = :driverId and e.createdAt >= :since")
    BigDecimal sumNetCreditByDriverIdSince(@Param("driverId") String driverId, @Param("since") Instant since);

    /** The Wallet screen's period-filtered Gross/Commission/Net card - same [since, until) range the transactions feed uses, so the two always agree. */
    @Query("""
            select coalesce(sum(e.grossFare), 0) as grossFare,
                   coalesce(sum(e.commissionAmount), 0) as commissionAmount,
                   coalesce(sum(e.netCredit), 0) as netCredit
            from EarningsTransaction e
            where e.driverId = :driverId and e.createdAt >= :since and e.createdAt < :until
            """)
    PeriodSums sumByDriverIdAndPeriod(@Param("driverId") String driverId, @Param("since") Instant since, @Param("until") Instant until);

    interface PeriodSums {
        BigDecimal getGrossFare();
        BigDecimal getCommissionAmount();
        BigDecimal getNetCredit();
    }
}
