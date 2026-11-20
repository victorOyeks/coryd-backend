package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DriverDocumentService {

    private final DriverDocumentRepository driverDocumentRepository;
    private final ReplacedFiles replacedFiles;
    private final VehicleRepository vehicleRepository;

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    /**
     * What a driver may submit from the app: a document for one of their own vehicles, not already expired. Without the
     * first a driver could attach their insurance to someone else's car; an expired certificate can only be rejected.
     */
    @Transactional(readOnly = true)
    public void checkSubmission(String driverId, CreateDriverDocumentRequest request) {
        if (request.vehicleId() != null && !request.vehicleId().isBlank() && vehicleRepository.findById(request.vehicleId())
                .filter(vehicle -> driverId.equals(vehicle.getDriverId())).isEmpty()) {
            throw new BadRequestException("That vehicle isn't registered to you");
        }
        if (request.expiresOn() != null && request.expiresOn().isBefore(LocalDate.now(LAGOS))) {
            throw new BadRequestException("This document has already expired - upload a current one");
        }
    }

    /** Upsert: one row per driver+documentType - resubmitting after a rejection updates the existing row and resets it to PENDING rather than growing a history. */
    @Transactional
    public DriverDocument create(String driverId, CreateDriverDocumentRequest request) {
        DriverDocument document = driverDocumentRepository.findByDriverIdAndDocumentType(driverId, request.documentType())
                .orElseGet(() -> DriverDocument.builder().driverId(driverId).documentType(request.documentType()).build());

        document.setVehicleId(request.vehicleId());
        replacedFiles.replaced(document.getDocumentPublicId(), document.getDocumentUrl(), request.publicId());
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
