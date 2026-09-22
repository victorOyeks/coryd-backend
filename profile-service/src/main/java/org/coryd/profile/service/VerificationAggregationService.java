package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverApprovedEvent;
import org.coryd.common.kafka.events.DriverRejectedEvent;
import org.coryd.profile.dto.DriverOnboardingStatusResponse;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.profile.dto.PendingVerificationItem;
import org.coryd.profile.dto.ReviewableItem;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.dto.DriverPrerequisiteStatus;
import org.coryd.profile.dto.VerificationStatusResponse;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** Combines every independently-reviewable driver item (identity, vehicle registration, documents) into one aggregate status - the single source of truth for both the driver-facing verification-status screen and the route-posting gate. Also the single place an ops admin decides any of those items, via decide() below - one call, keyed by driverId alone, instead of a separate endpoint (and a separate id to look up first) per item type. */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationAggregationService {

    private final DriverProfileRepository driverProfileRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverDocumentRepository driverDocumentRepository;
    private final CloudinaryService cloudinaryService;
    private final VerificationCheckRepository verificationCheckRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public VerificationStatusResponse getStatus(String driverId) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId).orElse(null);
        Vehicle vehicle = primaryVehicle(driverId);
        List<DriverDocument> documents = driverDocumentRepository.findByDriverId(driverId);

        return new VerificationStatusResponse(
                new VerificationStatusResponse.ItemStatus(
                        profile == null ? VerificationStatus.PENDING : profile.getGovernmentIdStatus(),
                        profile == null ? null : profile.getGovernmentIdRejectionReason(),
                        profile == null ? null : profile.getGovernmentIdUrl()),
                new VerificationStatusResponse.VehicleRegistrationStatus(
                        vehicle == null ? VerificationStatus.PENDING : vehicle.getVerificationStatus(),
                        vehicle == null ? null : vehicle.getRejectionReason(),
                        vehicle == null ? null : new VerificationStatusResponse.VehicleFields(
                                vehicle.getMake(), vehicle.getModel(), vehicle.getYear(), vehicle.getColor(), vehicle.getPlateNumber(),
                                vehicle.getExteriorPhotoUrl(), vehicle.getInteriorPhotoUrl(), vehicle.isHasAC(), vehicle.isAcConfirmedWorking())),
                documentStatuses(documents),
                computeOverallStatus(profile, vehicle, documents),
                profile == null ? null : profile.getVerifiedSince(),
                providerChecks(driverId)
        );
    }

    /** Ops admin's single decision call - approve/reject any/all of a driver's reviewable items in one request, then re-evaluate and publish once at the end (not once per item, which would fire redundant Kafka events/emails for a partial state). Returns the resulting status so the ops screen doesn't need a second call to refresh. */
    @Transactional
    public VerificationStatusResponse decide(String driverId, UserRole callerRole, VerificationDecisionBatchRequest request) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can decide driver verification items");
        }

        for (ItemDecision decision : request.decisions()) {
            applyDecision(driverId, decision);
        }

        reevaluateAndPublish(driverId, request.reviewedBy());
        return getStatus(driverId);
    }

    public List<PendingVerificationItem> getPendingQueue(UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can view the verification queue");
        }

        Map<String, List<ReviewableItem>> pendingByDriver = new LinkedHashMap<>();

        for (DriverProfile profile : driverProfileRepository.findByGovernmentIdStatus(VerificationStatus.IN_REVIEW)) {
            pendingByDriver.computeIfAbsent(profile.getUserId(), id -> new ArrayList<>()).add(ReviewableItem.GOVERNMENT_ID);
        }
        for (Vehicle vehicle : vehicleRepository.findByVerificationStatus(VerificationStatus.IN_REVIEW)) {
            pendingByDriver.computeIfAbsent(vehicle.getDriverId(), id -> new ArrayList<>()).add(ReviewableItem.VEHICLE);
        }
        for (DriverDocument document : driverDocumentRepository.findByStatus(VerificationStatus.IN_REVIEW)) {
            ReviewableItem item = document.getDocumentType() == DocumentType.INSURANCE ? ReviewableItem.INSURANCE : ReviewableItem.ROADWORTHINESS_CERT;
            pendingByDriver.computeIfAbsent(document.getDriverId(), id -> new ArrayList<>()).add(item);
        }

        if (pendingByDriver.isEmpty()) {
            return List.of();
        }

        Map<String, DriverProfile> profiles = driverProfileRepository.findByUserIdIn(new ArrayList<>(pendingByDriver.keySet())).stream()
                .collect(Collectors.toMap(DriverProfile::getUserId, profile -> profile));

        return pendingByDriver.entrySet().stream()
                .map(entry -> {
                    DriverProfile profile = profiles.get(entry.getKey());
                    return new PendingVerificationItem(entry.getKey(),
                            profile == null ? null : profile.getFirstName(),
                            profile == null ? null : profile.getLastName(),
                            entry.getValue());
                })
                .toList();
    }

    /** Newest first - a re-checked licence leaves the superseded row behind, and ops wants the current one. */
    private List<VerificationStatusResponse.ProviderCheck> providerChecks(String driverId) {
        return verificationCheckRepository.findByDriverId(driverId).stream()
                .map(check -> new VerificationStatusResponse.ProviderCheck(
                        check.getCheckType(), check.getOutcome(), check.getNameMatchScore(),
                        check.getProviderSummary(), check.getUpdatedAt()))
                .sorted(Comparator.comparing(VerificationStatusResponse.ProviderCheck::checkedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private void applyDecision(String driverId, ItemDecision decision) {
        switch (decision.item()) {
            case GOVERNMENT_ID -> {
                DriverProfile profile = driverProfileRepository.findByUserId(driverId)
                        .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
                profile.setGovernmentIdStatus(decision.status());
                profile.setGovernmentIdRejectionReason(decision.status() == VerificationStatus.REJECTED ? decision.rejectionReason() : null);
                driverProfileRepository.save(profile);
            }
            case VEHICLE -> {
                Vehicle vehicle = primaryVehicle(driverId);
                if (vehicle == null) {
                    throw new ResourceNotFoundException("No vehicle registered for this driver");
                }
                vehicle.setVerificationStatus(decision.status());
                vehicle.setRejectionReason(decision.status() == VerificationStatus.REJECTED ? decision.rejectionReason() : null);
                if (decision.status() == VerificationStatus.APPROVED) {
                    deleteFromCloudinary(vehicle.getExteriorPhotoPublicId(), "image");
                    deleteFromCloudinary(vehicle.getInteriorPhotoPublicId(), "image");
                    vehicle.setExteriorPhotoUrl(null);
                    vehicle.setExteriorPhotoPublicId(null);
                    vehicle.setInteriorPhotoUrl(null);
                    vehicle.setInteriorPhotoPublicId(null);
                }
                vehicleRepository.save(vehicle);
            }
            case INSURANCE, ROADWORTHINESS_CERT -> {
                DocumentType type = decision.item() == ReviewableItem.INSURANCE ? DocumentType.INSURANCE : DocumentType.ROADWORTHINESS_CERT;
                DriverDocument document = driverDocumentRepository.findByDriverIdAndDocumentType(driverId, type)
                        .orElseThrow(() -> new ResourceNotFoundException(type + " has not been uploaded for this driver"));
                document.setStatus(decision.status());
                document.setRejectionReason(decision.status() == VerificationStatus.REJECTED ? decision.rejectionReason() : null);
                if (decision.status() == VerificationStatus.APPROVED) {
                    deleteFromCloudinary(document.getDocumentPublicId(), "raw");
                    document.setDocumentUrl(null);
                    document.setDocumentPublicId(null);
                }
                driverDocumentRepository.save(document);
            }
        }
    }

    private void deleteFromCloudinary(String publicId, String resourceType) {
        if (publicId == null) {
            return;
        }
        try {
            cloudinaryService.delete(publicId, resourceType);
        } catch (Exception e) {
            log.warn("Could not delete verification asset {} from Cloudinary, leaving it orphaned: {}", publicId, e.getMessage());
        }
    }

    /**
     * Route-posting prerequisites. Identity and vehicle only - insurance and roadworthiness are
     * what the grace window defers. Reported as both submitted and approved so trip-service can
     * enforce either level without a second call.
     */
    @Transactional(readOnly = true)
    public DriverPrerequisiteStatus getPrerequisiteStatus(String driverId) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId).orElse(null);
        Vehicle vehicle = primaryVehicle(driverId);

        boolean identitySubmitted = profile != null && profile.getGovernmentIdStatus() != VerificationStatus.PENDING;
        boolean identityApproved = profile != null
                && profile.getGovernmentIdStatus() == VerificationStatus.APPROVED;

        return new DriverPrerequisiteStatus(
                identitySubmitted,
                vehicle != null,
                identityApproved,
                vehicle != null && vehicle.getVerificationStatus() == VerificationStatus.APPROVED);
    }

    /** Rejection reasons are personal - a driver sees only their own, ops sees any. */
    @Transactional(readOnly = true)
    public VerificationStatusResponse getStatusFor(String driverId, String callerId, UserRole callerRole) {
        if (!driverId.equals(callerId) && callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("You can only view your own verification status");
        }
        return getStatus(driverId);
    }

    /** Driver app's onboarding checklist - which steps the driver has actually done, distinct from getStatus()'s approval view (a submitted-but-pending-review item reads "done" here, since it's ops's turn next, not the driver's). */
    public DriverOnboardingStatusResponse getOnboardingStatus(String driverId) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId).orElse(null);
        Vehicle vehicle = primaryVehicle(driverId);
        List<DriverDocument> documents = driverDocumentRepository.findByDriverId(driverId);

        boolean profileSubmitted = profile != null && profile.getFirstName() != null && profile.getDateOfBirth() != null;
        boolean governmentIdSubmitted = profile != null && profile.getGovernmentIdUrl() != null;
        boolean vehicleSubmitted = vehicle != null;
        boolean documentsSubmitted = Arrays.stream(DocumentType.values())
                .allMatch(type -> findByType(documents, type) != null);

        return new DriverOnboardingStatusResponse(
                profileSubmitted, governmentIdSubmitted, vehicleSubmitted, documentsSubmitted,
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

        // verifiedSince is a one-way flag (never reset once set), so "still null" is exactly
        // "this is the first time ever" - a document-expiry-driven re-approval later must not
        // re-fire the "You're verified!" welcome push every time a renewed doc gets re-approved.
        boolean isFirstApproval = overall == VerificationStatus.APPROVED && profile != null && profile.getVerifiedSince() == null;
        if (isFirstApproval) {
            profile.setVerifiedSince(Instant.now());
            driverProfileRepository.save(profile);
        }

        if (isFirstApproval) {
            logKafkaResult(driverId, KafkaTopics.DRIVER_APPROVED,
                    kafkaTemplate.send(KafkaTopics.DRIVER_APPROVED, DriverApprovedEvent.builder()
                            .driverId(driverId)
                            .vehicleId(vehicleId)
                            .email(email)
                            .reviewedBy(reviewedBy)
                            .approvedAt(LocalDateTime.now())
                            .build()));
        } else if (overall == VerificationStatus.REJECTED) {
            logKafkaResult(driverId, KafkaTopics.DRIVER_REJECTED,
                    kafkaTemplate.send(KafkaTopics.DRIVER_REJECTED, DriverRejectedEvent.builder()
                            .driverId(driverId)
                            .vehicleId(vehicleId)
                            .email(email)
                            .rejectionReason(firstRejectionReason(profile, vehicle, documents))
                            .reviewedBy(reviewedBy)
                            .rejectedAt(LocalDateTime.now())
                            .build()));
        }
    }

    /** KafkaTemplate.send() is fire-and-forget by default - a broker-side failure (topic
     * missing, connection issue, whatever) would otherwise vanish silently, leaving
     * trip-service's route-posting gate out of sync with no trace of why. */
    private void logKafkaResult(String driverId, String topic, CompletableFuture<SendResult<String, Object>> future) {
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish {} for driver {}: {}", topic, driverId, ex.getMessage(), ex);
            } else {
                log.info("Published {} for driver {} - partition={} offset={}", topic, driverId,
                        result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            }
        });
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
                    doc == null ? null : doc.getExpiresOn(),
                    doc == null ? null : doc.getDocumentUrl()));
        }
        return result;
    }

    /** A document type that's never been uploaded still gates overall approval - PENDING, not silently satisfied. */
    private VerificationStatus computeOverallStatus(DriverProfile profile, Vehicle vehicle, List<DriverDocument> documents) {
        List<VerificationStatus> statuses = new ArrayList<>();
        statuses.add(profile == null ? VerificationStatus.PENDING : profile.getGovernmentIdStatus());
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
        // Anything still PENDING means the driver has not finished submitting, and that outranks
        // IN_REVIEW: the next move is theirs, not ops's.
        if (statuses.contains(VerificationStatus.PENDING)) {
            return VerificationStatus.PENDING;
        }
        return VerificationStatus.IN_REVIEW;
    }

    private String firstRejectionReason(DriverProfile profile, Vehicle vehicle, List<DriverDocument> documents) {
        if (profile != null && profile.getGovernmentIdStatus() == VerificationStatus.REJECTED) {
            return profile.getGovernmentIdRejectionReason();
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
