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
}
