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
import org.coryd.common.kafka.events.VerificationItemDecidedEvent;
import org.coryd.profile.dto.DriverOnboardingStatusResponse;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.common.enums.ReviewableItem;
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
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.dto.VerificationQueueCountsResponse;
import org.coryd.profile.dto.VerificationQueueItem;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
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
    private final DriverIdentity driverIdentity;

    public VerificationStatusResponse getStatus(String driverId) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId).orElse(null);
        Vehicle vehicle = primaryVehicle(driverId);
        List<DriverDocument> documents = driverDocumentRepository.findByDriverId(driverId);

        List<VerificationQueueItem.Entry> entries = entries(profile, vehicle, documents);
        return new VerificationStatusResponse(
                profile == null ? null : new VerificationStatusResponse.DriverDetails(profile.getFirstName(), profile.getLastName(),
                        profile.getPhotoUrl(), profile.getIdNumber(), profile.getDateOfBirth()),
                waitingSince(entries),
                new VerificationStatusResponse.ItemStatus(
                        profile == null ? VerificationStatus.PENDING : profile.getGovernmentIdStatus(),
                        profile == null ? null : profile.getGovernmentIdRejectionReason(),
                        profile == null ? null : profile.getGovernmentIdUrl(),
                        profile == null ? null : profile.getGovernmentIdSubmittedAt(),
                        profile != null && profile.isGovernmentIdResubmitted()),
                new VerificationStatusResponse.VehicleRegistrationStatus(
                        vehicle == null ? VerificationStatus.PENDING : vehicle.getVerificationStatus(),
                        vehicle == null ? null : vehicle.getRejectionReason(),
                        vehicle == null ? null : new VerificationStatusResponse.VehicleFields(
                                vehicle.getMake(), vehicle.getModel(), vehicle.getYear(), vehicle.getColor(), vehicle.getPlateNumber(),
                                vehicle.getExteriorPhotoUrl(), vehicle.getInteriorPhotoUrl(), vehicle.isHasAC(), vehicle.isAcConfirmedWorking()),
                        vehicle == null ? null : vehicle.getSubmittedAt(),
                        vehicle != null && vehicle.isResubmitted()),
                documentStatuses(documents),
                computeOverallStatus(profile, vehicle, documents),
                profile == null ? null : profile.getVerifiedSince(),
                providerChecks(driverId)
        );
    }

    /** Ops admin's single decision call - approve/reject any/all of a driver's reviewable items in one request. Each item notifies the driver on its own, then the aggregate is re-evaluated once at the end so the overall status only moves after every decision has landed. Returns the resulting status so the ops screen doesn't need a second call to refresh. */
    @Transactional
    public VerificationStatusResponse decide(String driverId, UserRole callerRole, VerificationDecisionBatchRequest request) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can decide driver verification items");
        }

        validate(request.decisions());
        String email = driverProfileRepository.findByUserId(driverId).map(DriverProfile::getEmail).orElse(null);
        for (ItemDecision decision : request.decisions()) {
            applyDecision(driverId, decision);
            publishItemDecision(driverId, email, decision, request.reviewedBy());
        }

        reevaluateAndPublish(driverId, request.reviewedBy());
        return getStatus(driverId);
    }

    /**
     * A licence Smile ID verified on its own - recorded exactly like an ops approval of the item, so the
     * driver hears about it and goes live if it was the last item outstanding.
     */
    @Transactional
    public void autoApproveGovernmentId(String driverId, String decidedBy) {
        ItemDecision decision = new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.APPROVED, null);
        String email = driverProfileRepository.findByUserId(driverId).map(DriverProfile::getEmail).orElse(null);
        applyDecision(driverId, decision);
        publishItemDecision(driverId, email, decision, decidedBy);
        reevaluateAndPublish(driverId, decidedBy);
        log.info("Driver {} licence approved by {}", driverId, decidedBy);
    }

    /** Per-item, because the aggregate events only fire on full approval or overall rejection - a driver whose insurance was approved while a document is still outstanding would otherwise hear nothing. */
    private void publishItemDecision(String driverId, String email, ItemDecision decision, String reviewedBy) {
        boolean approved = decision.status() == VerificationStatus.APPROVED;
        logKafkaResult(driverId, KafkaTopics.VERIFICATION_ITEM_DECIDED,
                kafkaTemplate.send(KafkaTopics.VERIFICATION_ITEM_DECIDED, VerificationItemDecidedEvent.builder()
                        .driverId(driverId)
                        .email(email)
                        .item(decision.item().name())
                        .itemLabel(decision.item().label())
                        .approved(approved)
                        .rejectionReason(approved ? null : decision.rejectionReason())
                        .reviewedBy(reviewedBy)
                        .decidedAt(LocalDateTime.now())
                        .build()));
    }

    /** Ops verification queue - every driver with an item waiting for a decision, longest-waiting first.
     * A null tab is "All". */
    public PagedResponse<VerificationQueueItem> queue(UserRole callerRole, VerificationQueueTab tab, String search, Pageable pageable) {
        requireOps(callerRole);
        List<VerificationQueueItem> rows = waiting(search).stream().filter(row -> tab == null || row.tab() == tab).toList();
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        return PagedResponse.from(new PageImpl<>(rows.subList(from, to), pageable, rows.size()));
    }

    /** The queue's tab badges - they follow the same search as the list. */
    public VerificationQueueCountsResponse queueCounts(UserRole callerRole, String search) {
        requireOps(callerRole);
        List<VerificationQueueItem> rows = waiting(search);
        Map<VerificationQueueTab, Long> byTab = rows.stream().collect(Collectors.groupingBy(VerificationQueueItem::tab, Collectors.counting()));
        return new VerificationQueueCountsResponse(rows.size(), byTab.getOrDefault(VerificationQueueTab.PENDING, 0L),
                byTab.getOrDefault(VerificationQueueTab.IN_REVIEW, 0L), byTab.getOrDefault(VerificationQueueTab.RESUBMITTED, 0L));
    }

    private List<VerificationQueueItem> waiting(String search) {
        Set<String> driverIds = new LinkedHashSet<>();
        driverProfileRepository.findByGovernmentIdStatus(VerificationStatus.IN_REVIEW).forEach(p -> driverIds.add(p.getUserId()));
        vehicleRepository.findByVerificationStatus(VerificationStatus.IN_REVIEW).forEach(v -> driverIds.add(v.getDriverId()));
        driverDocumentRepository.findByStatus(VerificationStatus.IN_REVIEW).forEach(d -> driverIds.add(d.getDriverId()));
        if (driverIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(driverIds);
        Map<String, DriverProfile> profiles = driverProfileRepository.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(DriverProfile::getUserId, p -> p));
        Map<String, List<Vehicle>> vehicles = vehicleRepository.findByDriverIdIn(ids).stream()
                .collect(Collectors.groupingBy(Vehicle::getDriverId));
        Map<String, List<DriverDocument>> documents = driverDocumentRepository.findByDriverIdIn(ids).stream()
                .collect(Collectors.groupingBy(DriverDocument::getDriverId));
        String term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);

        return ids.stream()
                .map(id -> {
                    DriverProfile profile = profiles.get(id);
                    List<VerificationQueueItem.Entry> entries = entries(profile, primary(vehicles.getOrDefault(id, List.of())),
                            documents.getOrDefault(id, List.of()));
                    return new VerificationQueueItem(id, profile == null ? null : profile.getFirstName(),
                            profile == null ? null : profile.getLastName(), profile == null ? null : profile.getPhotoUrl(),
                            tabOf(entries), waitingSince(entries), entries);
                })
                .filter(row -> term == null || matches(row, term))
                .sorted(Comparator.comparing(VerificationQueueItem::waitingSince, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** The four items in review order - an item never submitted reads PENDING. */
    private List<VerificationQueueItem.Entry> entries(DriverProfile profile, Vehicle vehicle, List<DriverDocument> documents) {
        List<VerificationQueueItem.Entry> entries = new ArrayList<>();
        entries.add(new VerificationQueueItem.Entry(ReviewableItem.GOVERNMENT_ID,
                profile == null ? VerificationStatus.PENDING : profile.getGovernmentIdStatus(),
                profile == null ? null : profile.getGovernmentIdSubmittedAt(), profile != null && profile.isGovernmentIdResubmitted()));
        entries.add(new VerificationQueueItem.Entry(ReviewableItem.VEHICLE,
                vehicle == null ? VerificationStatus.PENDING : vehicle.getVerificationStatus(),
                vehicle == null ? null : vehicle.getSubmittedAt(), vehicle != null && vehicle.isResubmitted()));
        for (DocumentType type : List.of(DocumentType.INSURANCE, DocumentType.ROADWORTHINESS_CERT)) {
            DriverDocument doc = findByType(documents, type);
            entries.add(new VerificationQueueItem.Entry(
                    type == DocumentType.INSURANCE ? ReviewableItem.INSURANCE : ReviewableItem.ROADWORTHINESS_CERT,
                    doc == null ? VerificationStatus.PENDING : doc.getStatus(),
                    doc == null ? null : doc.getSubmittedAt(), doc != null && doc.isResubmitted()));
        }
        return entries;
    }

    /** RESUBMITTED beats IN_REVIEW beats PENDING - see VerificationQueueTab. */
    private static VerificationQueueTab tabOf(List<VerificationQueueItem.Entry> entries) {
        boolean resubmitted = entries.stream().anyMatch(e -> e.status() == VerificationStatus.IN_REVIEW && e.resubmitted());
        if (resubmitted) {
            return VerificationQueueTab.RESUBMITTED;
        }
        boolean anyDecided = entries.stream().anyMatch(e -> e.status() == VerificationStatus.APPROVED || e.status() == VerificationStatus.REJECTED);
        return anyDecided ? VerificationQueueTab.IN_REVIEW : VerificationQueueTab.PENDING;
    }

    private static Instant waitingSince(List<VerificationQueueItem.Entry> entries) {
        return entries.stream()
                .filter(e -> e.status() == VerificationStatus.IN_REVIEW && e.submittedAt() != null)
                .map(VerificationQueueItem.Entry::submittedAt)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** By name, or by driver id / the console's "D-" reference. */
    private static boolean matches(VerificationQueueItem row, String term) {
        String name = ((row.firstName() == null ? "" : row.firstName()) + " " + (row.lastName() == null ? "" : row.lastName()))
                .toLowerCase(Locale.ROOT);
        String id = term.startsWith("d-") ? term.substring(2) : term;
        return name.contains(term) || row.driverId().replace("-", "").toLowerCase(Locale.ROOT).startsWith(id.replace("-", ""));
    }

    private static void requireOps(UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can view the verification queue");
        }
    }

    /** Ops decides with approve or reject only, and every rejection carries its own reason - it is shown to the driver as-is. */
    private static void validate(List<ItemDecision> decisions) {
        Set<ReviewableItem> seen = new HashSet<>();
        for (ItemDecision decision : decisions) {
            if (!seen.add(decision.item())) {
                throw new BadRequestException(decision.item().label() + " appears more than once in this decision");
            }
            if (decision.status() != VerificationStatus.APPROVED && decision.status() != VerificationStatus.REJECTED) {
                throw new BadRequestException("A decision must be APPROVED or REJECTED");
            }
            if (decision.status() == VerificationStatus.REJECTED
                    && (decision.rejectionReason() == null || decision.rejectionReason().isBlank())) {
                throw new BadRequestException("Give a reason for rejecting the " + decision.item().label().toLowerCase(Locale.ROOT));
            }
        }
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
                profile.setGovernmentIdResubmitted(false);
                profile.setGovernmentIdRejectionReason(decision.status() == VerificationStatus.REJECTED ? decision.rejectionReason() : null);
                if (decision.status() == VerificationStatus.APPROVED) {
                    purgeGovernmentId(profile);
                }
                driverProfileRepository.save(profile);
                if (decision.status() == VerificationStatus.APPROVED) {
                    driverIdentity.completeIfReady(driverId);
                }
            }
            case VEHICLE -> {
                Vehicle vehicle = primaryVehicle(driverId);
                if (vehicle == null) {
                    throw new ResourceNotFoundException("No vehicle registered for this driver");
                }
                vehicle.setVerificationStatus(decision.status());
                vehicle.setResubmitted(false);
                vehicle.setRejectionReason(decision.status() == VerificationStatus.REJECTED ? decision.rejectionReason() : null);
                if (decision.status() == VerificationStatus.APPROVED) {
                    deleteFromCloudinary(vehicle.getExteriorPhotoPublicId(),
                            CloudinaryService.resourceTypeFromUrl(vehicle.getExteriorPhotoUrl()));
                    deleteFromCloudinary(vehicle.getInteriorPhotoPublicId(),
                            CloudinaryService.resourceTypeFromUrl(vehicle.getInteriorPhotoUrl()));
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
                document.setResubmitted(false);
                document.setRejectionReason(decision.status() == VerificationStatus.REJECTED ? decision.rejectionReason() : null);
                if (decision.status() == VerificationStatus.APPROVED) {
                    deleteFromCloudinary(document.getDocumentPublicId(),
                            CloudinaryService.resourceTypeFromUrl(document.getDocumentUrl()));
                    document.setDocumentUrl(null);
                    document.setDocumentPublicId(null);
                }
                driverDocumentRepository.save(document);
            }
        }
    }

    /** An approved licence image is never shown again. */
    void purgeGovernmentId(DriverProfile profile) {
        deleteFromCloudinary(profile.getGovernmentIdPublicId(),
                CloudinaryService.resourceTypeFromUrl(profile.getGovernmentIdUrl()));
        profile.setGovernmentIdUrl(null);
        profile.setGovernmentIdPublicId(null);
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
                vehicle != null && vehicle.getVerificationStatus() == VerificationStatus.APPROVED,
                driverIdentity.verified(driverId));
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
        boolean governmentIdSubmitted = profile != null && profile.getGovernmentIdStatus() != VerificationStatus.PENDING;
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
        return primary(vehicleRepository.findByDriverId(driverId));
    }

    /** The first one registered - picked the same way everywhere so the queue, review page and decision agree. */
    private static Vehicle primary(List<Vehicle> vehicles) {
        return vehicles.stream()
                .min(Comparator.comparing(Vehicle::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
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
                    doc == null ? null : doc.getDocumentUrl(),
                    doc == null ? null : doc.getSubmittedAt(),
                    doc != null && doc.isResubmitted()));
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
        // Worst-first. IN_REVIEW is the only non-approved state the driver cannot act on.
        if (statuses.contains(VerificationStatus.EXPIRED)) {
            return VerificationStatus.EXPIRED;
        }
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
