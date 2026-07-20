package org.coryd.payment.repository;

import org.coryd.payment.entity.EarningsTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EarningsTransactionRepository extends JpaRepository<EarningsTransaction, String> {
    List<EarningsTransaction> findByDriverId(String driverId);
}
