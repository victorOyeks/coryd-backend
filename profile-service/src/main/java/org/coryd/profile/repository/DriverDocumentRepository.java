package org.coryd.profile.repository;

import org.coryd.common.enums.DocumentType;
import org.coryd.profile.entity.DriverDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, String> {
    List<DriverDocument> findByDriverId(String driverId);
    Optional<DriverDocument> findByDriverIdAndDocumentType(String driverId, DocumentType documentType);
    List<DriverDocument> findByExpiresOnBeforeAndReminderSent14dFalse(LocalDate date);
    List<DriverDocument> findByExpiresOnBeforeAndReminderSent3dFalse(LocalDate date);
    List<DriverDocument> findByExpiresOnBefore(LocalDate date);
}
