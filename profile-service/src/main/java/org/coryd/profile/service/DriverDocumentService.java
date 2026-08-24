package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.dto.VerificationDecisionRequest;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverDocumentService {

    private final DriverDocumentRepository driverDocumentRepository;
    private final CloudinaryService cloudinaryService;
    private final VerificationAggregationService verificationAggregationService;

    /** Upsert: one row per driver+documentType - resubmitting after a rejection updates the existing row and resets it to PENDING rather than growing a history. */
    @Transactional
    public DriverDocument create(String driverId, CreateDriverDocumentRequest request) {
        DriverDocument document = driverDocumentRepository.findByDriverIdAndDocumentType(driverId, request.documentType())
                .orElseGet(() -> DriverDocument.builder().driverId(driverId).documentType(request.documentType()).build());

        document.setVehicleId(request.vehicleId());
        document.setDocumentUrl(request.url());
        document.setDocumentPublicId(request.publicId());
        document.setExpiresOn(request.expiresOn());
        document.setStatus(VerificationStatus.PENDING);
        document.setRejectionReason(null);
        document.setReminderSent14d(false);
        document.setReminderSent3d(false);

        return driverDocumentRepository.save(document);
    }

    public List<DriverDocument> findByDriver(String driverId) {
        return driverDocumentRepository.findByDriverId(driverId);
    }

    @Transactional
    public DriverDocument decide(String documentId, UserRole callerRole, VerificationDecisionRequest request) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can decide document verification");
        }
        DriverDocument document = driverDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        document.setStatus(request.status());
        document.setRejectionReason(request.status() == VerificationStatus.REJECTED ? request.rejectionReason() : null);

        if (request.status() == VerificationStatus.APPROVED && document.getDocumentPublicId() != null) {
            try {
                cloudinaryService.delete(document.getDocumentPublicId(), "raw");
            } catch (Exception e) {
                log.warn("Could not delete document from Cloudinary, leaving it orphaned: {}", e.getMessage());
            }
            document.setDocumentUrl(null);
            document.setDocumentPublicId(null);
        }

        driverDocumentRepository.save(document);
        verificationAggregationService.reevaluateAndPublish(document.getDriverId(), request.reviewedBy());
        return document;
    }
}
