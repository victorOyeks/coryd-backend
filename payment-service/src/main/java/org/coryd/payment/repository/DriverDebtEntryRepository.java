package org.coryd.payment.repository;

import org.coryd.payment.entity.DriverDebtEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverDebtEntryRepository extends JpaRepository<DriverDebtEntry, String> {
    Page<DriverDebtEntry> findByDriverIdOrderByCreatedAtDescIdDesc(String driverId, Pageable pageable);
}
