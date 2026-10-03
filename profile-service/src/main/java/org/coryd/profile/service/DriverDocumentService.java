package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DriverDocumentService {

    private final DriverDocumentRepository driverDocumentRepository;

    /** Upsert: one row per driver+documentType - resubmitting after a rejection updates the existing row and resets it to PENDING rather than growing a history. */
    @Transactional
    public DriverDocument create(String driverId, CreateDriverDocumentRequest request) {
        DriverDocument document = driverDocumentRepository.findByDriverIdAndDocumentType(driverId, request.documentType())
                .orElseGet(() -> DriverDocument.builder().driverId(driverId).documentType(request.documentType()).build());

        document.setVehicleId(request.vehicleId());
        document.setDocumentUrl(request.url());
        document.setDocumentPublicId(request.publicId());
        document.setExpiresOn(request.expiresOn());
        if (document.getStatus() != VerificationStatus.IN_REVIEW) {
            document.setSubmittedAt(Instant.now());
            document.setResubmitted(document.getStatus() == VerificationStatus.REJECTED);
        }
        document.setStatus(VerificationStatus.IN_REVIEW);
        document.setRejectionReason(null);
        document.setReminderSent14d(false);
        document.setReminderSent3d(false);

        return driverDocumentRepository.save(document);
    }

    public List<DriverDocument> findByDriver(String driverId) {
        return driverDocumentRepository.findByDriverId(driverId);
    }
}
