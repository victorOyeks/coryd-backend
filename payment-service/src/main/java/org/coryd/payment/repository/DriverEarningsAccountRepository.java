package org.coryd.payment.repository;

import org.coryd.payment.entity.DriverEarningsAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface DriverEarningsAccountRepository extends JpaRepository<DriverEarningsAccount, String> {
    Optional<DriverEarningsAccount> findByDriverId(String driverId);

    /** Atomic conditional debit - same last-seat/race-condition fix as RoutePostRepository.holdSeats: 0 rows affected means insufficient balance, resolved in one statement instead of a check-then-update race. */
    @Modifying
    @Query("""
            update DriverEarningsAccount d
            set d.availableBalance = d.availableBalance - :amount
            where d.driverId = :driverId and d.availableBalance >= :amount
            """)
    int debitBalance(@Param("driverId") String driverId, @Param("amount") BigDecimal amount);

    /** Compensating refund - a withdrawal that failed (synchronously or via a later transfer.failed webhook) after the eager debit above must give the balance back. */
    @Modifying
    @Query("""
            update DriverEarningsAccount d
            set d.availableBalance = d.availableBalance + :amount
            where d.driverId = :driverId
            """)
    int creditBalance(@Param("driverId") String driverId, @Param("amount") BigDecimal amount);
}
