package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverApprovedEvent;
import org.coryd.common.kafka.events.DriverRejectedEvent;
import org.coryd.profile.dto.DriverOnboardingStatusResponse;
import org.coryd.profile.dto.VerificationStatusResponse;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Combines every independently-reviewable driver item (identity, vehicle registration, documents) into one aggregate status - the single source of truth for both the driver-facing verification-status screen and the route-posting gate. */
@Service
@RequiredArgsConstructor
public class VerificationAggregationService {

    private final DriverProfileRepository driverProfileRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverDocumentRepository driverDocumentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public VerificationStatusResponse getStatus(String driverId) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId).orElse(null);
        Vehicle vehicle = primaryVehicle(driverId);
        List<DriverDocument> documents = driverDocumentRepository.findByDriverId(driverId);

        return new VerificationStatusResponse(
                new VerificationStatusResponse.ItemStatus(
                        profile == null ? VerificationStatus.PENDING : profile.getGovernmentIdStatus(),
                        profile == null ? null : profile.getGovernmentIdRejectionReason()),
                new VerificationStatusResponse.ItemStatus(
                        profile == null ? VerificationStatus.PENDING : profile.getSelfieStatus(),
                        profile == null ? null : profile.getSelfieRejectionReason()),
                new VerificationStatusResponse.VehicleRegistrationStatus(
                        vehicle == null ? VerificationStatus.PENDING : vehicle.getVerificationStatus(),
                        vehicle == null ? null : vehicle.getRejectionReason(),
                        vehicle == null ? null : new VerificationStatusResponse.VehicleFields(
                                vehicle.getMake(), vehicle.getModel(), vehicle.getYear(), vehicle.getColor(), vehicle.getPlateNumber())),
                documentStatuses(documents),
                computeOverallStatus(profile, vehicle, documents)
        );
    }

    /** Driver app's onboarding checklist - which steps the driver has actually done, distinct from getStatus()'s approval view (a submitted-but-pending-review item reads "done" here, since it's ops's turn next, not the driver's). */
    public DriverOnboardingStatusResponse getOnboardingStatus(String driverId) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId).orElse(null);
        Vehicle vehicle = primaryVehicle(driverId);
        List<DriverDocument> documents = driverDocumentRepository.findByDriverId(driverId);

        boolean profileSubmitted = profile != null && profile.getLegalName() != null && profile.getDateOfBirth() != null;
        boolean governmentIdSubmitted = profile != null && profile.getGovernmentIdUrl() != null;
        boolean selfieSubmitted = profile != null && profile.getSelfieUrl() != null;
        boolean vehicleSubmitted = vehicle != null;
        boolean documentsSubmitted = Arrays.stream(DocumentType.values())
                .allMatch(type -> findByType(documents, type) != null);

        return new DriverOnboardingStatusResponse(
                profileSubmitted, governmentIdSubmitted, selfieSubmitted, vehicleSubmitted, documentsSubmitted,
                computeOverallStatus(profile, vehicle, documents)
        );
    }

    /** Recomputes the driver's overall status and publishes DriverApprovedEvent/DriverRejectedEvent if it's now fully approved or has a rejection - called after every item-level decide() so trip-service's route-posting gate stays in sync. */
    public void reevaluateAndPublish(String driverId, String reviewedBy) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId).orElse(null);
        Vehicle vehicle = primaryVehicle(driverId);
        List<DriverDocument> documents = driverDocumentRepository.findByDriverId(driverId);

        VerificationStatus overall = computeOverallStatus(profile, vehicle, documents);
        String vehicleId = vehicle == null ? null : vehicle.getId();
        String email = profile == null ? null : profile.getEmail();

        if (overall == VerificationStatus.APPROVED) {
            kafkaTemplate.send(KafkaTopics.DRIVER_APPROVED, DriverApprovedEvent.builder()
                    .driverId(driverId)
                    .vehicleId(vehicleId)
                    .email(email)
                    .reviewedBy(reviewedBy)
                    .approvedAt(LocalDateTime.now())
                    .build());
        } else if (overall == VerificationStatus.REJECTED) {
            kafkaTemplate.send(KafkaTopics.DRIVER_REJECTED, DriverRejectedEvent.builder()
                    .driverId(driverId)
                    .vehicleId(vehicleId)
                    .email(email)
                    .rejectionReason(firstRejectionReason(profile, vehicle, documents))
                    .reviewedBy(reviewedBy)
                    .rejectedAt(LocalDateTime.now())
                    .build());
        }
    }

    private Vehicle primaryVehicle(String driverId) {
        List<Vehicle> vehicles = vehicleRepository.findByDriverId(driverId);
        return vehicles.isEmpty() ? null : vehicles.get(0);
    }

    private List<VerificationStatusResponse.DocumentStatus> documentStatuses(List<DriverDocument> documents) {
        List<VerificationStatusResponse.DocumentStatus> result = new ArrayList<>();
        for (DocumentType type : DocumentType.values()) {
            DriverDocument doc = findByType(documents, type);
            result.add(new VerificationStatusResponse.DocumentStatus(
                    type,
                    doc == null ? VerificationStatus.PENDING : doc.getStatus(),
                    doc == null ? null : doc.getRejectionReason(),
                    doc == null ? null : doc.getExpiresOn()));
        }
        return result;
    }

    /** A document type that's never been uploaded still gates overall approval - PENDING, not silently satisfied. */
    private VerificationStatus computeOverallStatus(DriverProfile profile, Vehicle vehicle, List<DriverDocument> documents) {
        List<VerificationStatus> statuses = new ArrayList<>();
        statuses.add(profile == null ? VerificationStatus.PENDING : profile.getGovernmentIdStatus());
        statuses.add(profile == null ? VerificationStatus.PENDING : profile.getSelfieStatus());
        statuses.add(vehicle == null ? VerificationStatus.PENDING : vehicle.getVerificationStatus());
        for (DocumentType type : DocumentType.values()) {
            DriverDocument doc = findByType(documents, type);
            statuses.add(doc == null ? VerificationStatus.PENDING : doc.getStatus());
        }

        if (statuses.contains(VerificationStatus.REJECTED)) {
            return VerificationStatus.REJECTED;
        }
        if (statuses.stream().allMatch(status -> status == VerificationStatus.APPROVED)) {
            return VerificationStatus.APPROVED;
        }
        return VerificationStatus.PENDING;
    }

    private String firstRejectionReason(DriverProfile profile, Vehicle vehicle, List<DriverDocument> documents) {
        if (profile != null && profile.getGovernmentIdStatus() == VerificationStatus.REJECTED) {
            return profile.getGovernmentIdRejectionReason();
        }
        if (profile != null && profile.getSelfieStatus() == VerificationStatus.REJECTED) {
            return profile.getSelfieRejectionReason();
        }
        if (vehicle != null && vehicle.getVerificationStatus() == VerificationStatus.REJECTED) {
            return vehicle.getRejectionReason();
        }
        return documents.stream()
                .filter(doc -> doc.getStatus() == VerificationStatus.REJECTED)
                .map(DriverDocument::getRejectionReason)
                .findFirst()
                .orElse(null);
    }

    private DriverDocument findByType(List<DriverDocument> documents, DocumentType type) {
        return documents.stream().filter(doc -> doc.getDocumentType() == type).findFirst().orElse(null);
    }
}
