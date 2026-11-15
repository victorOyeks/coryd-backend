package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.OnboardingStep;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.RiderIdentityStatus;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.entity.BaseEntity;
import org.coryd.profile.dto.VerificationQueueItem;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.entity.VerificationDecisionLog;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where each rider and driver stands in signing up, for notification-service's onboarding reminders
 * (docs/ops-console/onboarding-reminders.md): the steps that are theirs to do, in the order they are asked for; whether
 * anything is with ops (or Smile ID) instead; whether they have finished; and when they last moved forward. Accepting the
 * terms lives in identity-service and is added by the caller. Batched - a fixed number of queries per call.
 *
 * <p>The identity (NIN) step reads the person's tab on the ops identity screen (RiderIdentityStatuses, IdentityReviewService
 * .tabOf); the driver's four items are the verification queue's (VerificationAggregationService.currentItems).
 */
@Service
@RequiredArgsConstructor
public class OnboardingStatusService {

    public static final int MAX_BATCH = 500;

    public record Person(String userId, List<String> roles) {
    }

    public record Step(OnboardingStep step, OnboardingStep.Reason reason) {
    }

    /**
     * One role's onboarding. complete: nothing left (rider: profile and NIN approved; driver: approved once). steps: theirs
     * to do, first is next. waitingOnOps: something is in review or with Smile ID. lastProgressAt: the last change to any of
     * their onboarding records - a submission, an upload, a decision on one.
     */
    public record RoleStatus(String role, boolean complete, List<Step> steps, boolean waitingOnOps, Instant lastProgressAt) {
    }

    public record Status(String userId, List<RoleStatus> roles) {
    }

    private final RiderProfileRepository riderProfileRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final SmileIdCheckRepository smileIdCheckRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverDocumentRepository driverDocumentRepository;
    private final VerificationDecisionLogRepository decisionLogRepository;
    private final RiderIdentityStatuses identityStatuses;
    private final VerificationAggregationService aggregation;

    @Transactional(readOnly = true)
    public List<Status> status(Collection<Person> people) {
        if (people == null || people.isEmpty()) {
            return List.of();
        }
        if (people.size() > MAX_BATCH) {
            throw new BadRequestException("At most " + MAX_BATCH + " people at a time");
        }
        List<String> ids = people.stream().map(Person::userId).filter(Objects::nonNull).distinct().toList();
        List<String> driverIds = people.stream().filter(p -> roles(p).contains("DRIVER")).map(Person::userId).distinct().toList();

        Map<String, RiderProfile> riders = byKey(riderProfileRepository.findByUserIdIn(ids), RiderProfile::getUserId);
        Map<String, DriverProfile> drivers = byKey(driverProfileRepository.findByUserIdIn(ids), DriverProfile::getUserId);
        Map<String, IdentityVerification> identities = byKey(identityVerificationRepository.findByUserIdIn(ids), IdentityVerification::getUserId);
        Map<String, SmileIdCheck> ninChecks = smileIdCheckRepository.findByCheckTypeInAndUserIdIn(IdentityReviewService.NIN_CHECKS, ids).stream()
                .collect(Collectors.toMap(SmileIdCheck::getUserId, Function.identity(), IdentityReviewService::newer));
        Map<String, RiderIdentityStatus> identityTabs = identityStatuses.of(ids);

        Map<String, List<VerificationQueueItem.Entry>> items = driverIds.isEmpty() ? Map.of() : aggregation.currentItems(driverIds);
        Map<String, SmileIdCheck> licenceChecks = driverIds.isEmpty() ? Map.of()
                : byKey(smileIdCheckRepository.findByCheckTypeAndUserIdIn(SmileIdCheckType.DRIVERS_LICENCE, driverIds), SmileIdCheck::getUserId);
        Map<String, List<Vehicle>> vehicles = driverIds.isEmpty() ? Map.of()
                : vehicleRepository.findByDriverIdIn(driverIds).stream().collect(Collectors.groupingBy(Vehicle::getDriverId));
        Map<String, List<DriverDocument>> documents = driverIds.isEmpty() ? Map.of()
                : driverDocumentRepository.findByDriverIdIn(driverIds).stream().collect(Collectors.groupingBy(DriverDocument::getDriverId));

        List<Status> out = new ArrayList<>();
        for (Person person : people) {
            String id = person.userId();
            if (id == null) {
                continue;
            }
            RiderIdentityStatus tab = identityTabs.getOrDefault(id, RiderIdentityStatus.NOT_STARTED);
            SmileIdCheck ninCheck = ninChecks.get(id);
            DriverProfile driver = drivers.get(id);
            Instant identityProgress = latest(identities.get(id), ninCheck);
            List<RoleStatus> roles = new ArrayList<>();
            for (String role : roles(person)) {
                if (role.equals("RIDER")) {
                    roles.add(rider(riders.get(id), tab, ninCheck, identityProgress));
                } else if (role.equals("DRIVER")) {
                    List<BaseEntity> records = new ArrayList<>();
                    records.add(driver);
                    records.addAll(vehicles.getOrDefault(id, List.of()));
                    records.addAll(documents.getOrDefault(id, List.of()));
                    Instant progress = latest(records.toArray(BaseEntity[]::new));
                    roles.add(driver(id, driver, tab, ninCheck, items.getOrDefault(id, List.of()), licenceChecks.get(id),
                            max(identityProgress, progress)));
                }
            }
            out.add(new Status(id, roles));
        }
        return out;
    }

    static RoleStatus rider(RiderProfile profile, RiderIdentityStatus tab, SmileIdCheck ninCheck, Instant identityProgress) {
        List<Step> steps = new ArrayList<>();
        if (profile == null) {
            steps.add(new Step(OnboardingStep.PROFILE, OnboardingStep.Reason.NOT_DONE));
        }
        IdentityTurn identity = identity(tab, ninCheck, false);
        if (identity.step() != null) {
            steps.add(identity.step());
        }
        boolean complete = profile != null && tab == RiderIdentityStatus.APPROVED;
        return new RoleStatus("RIDER", complete, complete ? List.of() : steps, !complete && identity.withOps(),
                max(profile == null ? null : max(profile.getCreatedAt(), profile.getUpdatedAt()), identityProgress));
    }

    RoleStatus driver(String driverId, DriverProfile profile, RiderIdentityStatus tab, SmileIdCheck ninCheck,
                      List<VerificationQueueItem.Entry> entries, SmileIdCheck licenceCheck, Instant lastProgressAt) {
        if (profile != null && profile.getVerifiedSince() != null) {
            return new RoleStatus("DRIVER", true, List.of(), false, lastProgressAt);
        }
        List<Step> steps = new ArrayList<>();
        if (profile == null || profile.getFirstName() == null || profile.getDateOfBirth() == null) {
            steps.add(new Step(OnboardingStep.PROFILE, OnboardingStep.Reason.NOT_DONE));
        }
        boolean ninAccepted = IdentityReviewService.ninAccepted(profile);
        IdentityTurn identity = identity(tab, ninCheck, ninAccepted);
        if (identity.step() != null) {
            steps.add(identity.step());
        }
        boolean withOps = identity.withOps();
        Set<ReviewableItem> resubmissionRequested = entries.stream().anyMatch(e -> e.status() == VerificationStatus.REJECTED)
                ? resubmissionRequested(driverId) : Set.of();
        for (VerificationQueueItem.Entry entry : entries) {
            if (entry.awaitingOps()) {
                withOps = true;
                continue;
            }
            OnboardingStep.Reason reason = switch (entry.status()) {
                case APPROVED, IN_REVIEW -> null;
                case PENDING -> entry.item() == ReviewableItem.GOVERNMENT_ID && licenceCheck != null && licenceCheck.getRetryReason() != null
                        ? OnboardingStep.Reason.RETRY : OnboardingStep.Reason.NOT_DONE;
                case REJECTED -> resubmissionRequested.contains(entry.item()) ? OnboardingStep.Reason.RESUBMIT : OnboardingStep.Reason.REJECTED;
                case EXPIRED -> OnboardingStep.Reason.EXPIRED;
            };
            if (reason != null) {
                steps.add(new Step(step(entry.item()), reason));
            }
        }
        boolean complete = steps.isEmpty() && !withOps && tab == RiderIdentityStatus.APPROVED
                && entries.stream().allMatch(e -> e.status() == VerificationStatus.APPROVED);
        return new RoleStatus("DRIVER", complete, steps, withOps, lastProgressAt);
    }

    /** The identity (NIN) step if it's theirs to do, and whether it's with ops or Smile ID instead. */
    record IdentityTurn(Step step, boolean withOps) {
    }

    static IdentityTurn identity(RiderIdentityStatus tab, SmileIdCheck ninCheck, boolean ninAccepted) {
        return switch (tab) {
            case APPROVED -> new IdentityTurn(null, false);
            case IN_REVIEW -> new IdentityTurn(null, true);
            case LOCKED_OUT -> new IdentityTurn(new Step(OnboardingStep.NIN_LOCKED_OUT, OnboardingStep.Reason.LOCKED_OUT), false);
            case FAILED -> new IdentityTurn(new Step(OnboardingStep.NIN, OnboardingStep.Reason.RETRY), false);
            // a driver whose NIN is confirmed waits only for the licence; a check still out with Smile ID is Smile ID's turn
            case PENDING -> ninAccepted ? new IdentityTurn(null, false)
                    : ninCheck != null && ninCheck.waitingForResult() ? new IdentityTurn(null, true)
                    : new IdentityTurn(new Step(OnboardingStep.NIN, OnboardingStep.Reason.NOT_DONE), false);
            case NOT_STARTED -> new IdentityTurn(new Step(OnboardingStep.NIN, OnboardingStep.Reason.NOT_DONE), false);
        };
    }

    static OnboardingStep step(ReviewableItem item) {
        return switch (item) {
            case GOVERNMENT_ID -> OnboardingStep.LICENCE;
            case VEHICLE -> OnboardingStep.VEHICLE;
            case INSURANCE -> OnboardingStep.INSURANCE;
            case ROADWORTHINESS_CERT -> OnboardingStep.ROADWORTHINESS;
        };
    }

    /** Items whose latest decision was a resubmission request - "upload it again" rather than "it was rejected". */
    private Set<ReviewableItem> resubmissionRequested(String driverId) {
        Map<DecisionSubject, VerificationDecisionLog> latest = new HashMap<>();
        decisionLogRepository.findByDriverIdOrderByCreatedAtDesc(driverId).forEach(l -> latest.putIfAbsent(l.getItem(), l));
        Set<ReviewableItem> out = EnumSet.noneOf(ReviewableItem.class);
        for (ReviewableItem item : ReviewableItem.values()) {
            VerificationDecisionLog last = latest.get(DecisionSubject.of(item));
            if (last != null && last.getAction() == VerificationDecisionAction.RESUBMISSION_REQUESTED) {
                out.add(item);
            }
        }
        return out;
    }

    private static List<String> roles(Person person) {
        return person.roles() == null ? List.of() : person.roles().stream().filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<String, T> byKey(List<T> rows, Function<T, String> key) {
        Map<String, T> out = new LinkedHashMap<>();
        rows.forEach(r -> out.putIfAbsent(key.apply(r), r));
        return out;
    }

    private static Instant latest(BaseEntity... rows) {
        Instant out = null;
        for (BaseEntity row : rows) {
            if (row != null) {
                out = max(out, max(row.getCreatedAt(), row.getUpdatedAt()));
            }
        }
        return out;
    }

    static Instant max(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }
}
