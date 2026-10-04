package org.coryd.payment.repository;

import org.coryd.payment.entity.DriverEarningsAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DriverEarningsAccountRepository extends JpaRepository<DriverEarningsAccount, String> {
    Optional<DriverEarningsAccount> findByDriverId(String driverId);

    /** Row lock so a trip completion and a debt being recorded can't overwrite each other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DriverEarningsAccount d where d.driverId = :driverId")
    Optional<DriverEarningsAccount> findForUpdate(@Param("driverId") String driverId);
}
