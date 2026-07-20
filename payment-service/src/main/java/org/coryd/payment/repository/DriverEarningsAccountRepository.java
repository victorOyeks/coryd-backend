package org.coryd.payment.repository;

import org.coryd.payment.entity.DriverEarningsAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DriverEarningsAccountRepository extends JpaRepository<DriverEarningsAccount, String> {
    Optional<DriverEarningsAccount> findByDriverId(String driverId);
}
