package org.coryd.profile.repository;

import org.coryd.profile.entity.DriverDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, String> {
    List<DriverDocument> findByDriverId(String driverId);
    List<DriverDocument> findByExpiresOnBeforeAndReminderSent14dFalse(LocalDate date);
    List<DriverDocument> findByExpiresOnBeforeAndReminderSent3dFalse(LocalDate date);
    List<DriverDocument> findByExpiresOnBefore(LocalDate date);
}
