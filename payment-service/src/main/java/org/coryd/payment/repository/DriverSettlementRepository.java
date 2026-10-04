package org.coryd.payment.repository;

import org.coryd.payment.entity.DriverSettlement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface DriverSettlementRepository extends JpaRepository<DriverSettlement, String> {
    Optional<DriverSettlement> findByGatewaySettlementId(String gatewaySettlementId);

    Page<DriverSettlement> findByDriverIdOrderBySettlementDateDescCreatedAtDesc(String driverId, Pageable pageable);

    @Query("select coalesce(sum(s.amount), 0) from DriverSettlement s where s.driverId = :driverId and s.status in :statuses")
    BigDecimal sumByDriverIdAndStatusIn(@Param("driverId") String driverId, @Param("statuses") Collection<String> statuses);

    @Query("select max(s.settlementDate) from DriverSettlement s where s.driverId = :driverId and s.status = 'success'")
    Instant lastSettledAt(@Param("driverId") String driverId);

    /** Ops report: what Paystack paid out to driver banks in the window. */
    interface Totals {
        long getCount();
        BigDecimal getAmount();
    }

    @Query("""
            select count(s) as count, coalesce(sum(s.amount), 0) as amount from DriverSettlement s
            where s.status = 'success' and s.settlementDate >= :from and s.settlementDate < :to
            """)
    Totals settledBetween(@Param("from") Instant from, @Param("to") Instant to);
}
