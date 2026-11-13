package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.IdentityVerificationQueueItem;
import org.coryd.profile.dto.IdentityVerificationResponse;
import org.coryd.profile.dto.PartialNameMatchItem;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.dto.SubmitIdentityVerificationRequest;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everyone's identity check during onboarding: NIN + live selfie through Smile ID. The app opens a check
 * through SmileIdCheckService, Smile ID's SDK captures and submits the selfie, and NinCheck decides from the result.
 * Anything a machine can't settle waits in the ops queue. Nobody is rejected by a machine - only an ops
 * decision rejects, with a reason the person sees.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityVerificationService {

    /** The older selfie upload's limit - a sixth attempt goes through support. Smile ID checks count theirs on smile_id_checks. */
    static final int MAX_ATTEMPTS = 5;

    private final IdentityVerificationRepository identityVerificationRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final VerificationCheckRepository verificationCheckRepository;
    private final IdentityVerificationDecider decider;
    private final SmileIdCheckRepository smileIdCheckRepository;
    private final VerifiedIdentityService verifiedIdentityService;
    private final SmileIdProperties smileIdProperties;

    /**
     * The older flow: a selfie uploaded through POST /media/upload, reviewed by ops by eye. Kept only so
     * app builds without Smile ID's SDK keep working - remove once every install is on the new build.
     */
    @Deprecated
    @Transactional
    public IdentityVerificationResponse submit(String userId, UserRole role, SubmitIdentityVerificationRequest request) {
        requireProfileName(userId, role);
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId)
                .orElseGet(() -> IdentityVerification.builder().userId(userId).build());
        if (verification.getStatus() == VerificationStatus.APPROVED) {
            throw new BadRequestException("Your identity is already verified");
        }
        if (verification.getAttempts() >= MAX_ATTEMPTS) {
            throw new BadRequestException("Too many attempts - contact CoRyd support to verify your identity");
        }

        // A resubmission replaces the previous selfie, which nobody needs to see again.
        if (verification.getSelfiePublicId() != null && !verification.getSelfiePublicId().equals(request.selfiePublicId())) {
            decider.purgeSelfie(verification);
        }
        Instant now = Instant.now();
        verification.setStatus(VerificationStatus.IN_REVIEW);
        verification.setNinLast4(request.nin().substring(7));
        verification.setSelfieUrl(request.selfieUrl());
        verification.setSelfiePublicId(request.selfiePublicId());
        verification.setConsentAt(now);
        verification.setSubmittedAt(now);
        verification.setDecidedAt(null);
        verification.setDecidedBy(null);
        verification.setRejectionReason(null);
        verification.setAttempts(verification.getAttempts() + 1);
        return IdentityVerificationResponse.from(identityVerificationRepository.save(verification), ninCheck(userId));
    }

    @Transactional(readOnly = true)
    public IdentityVerificationResponse mine(String userId) {
        return identityVerificationRepository.findByUserId(userId)
                .map(v -> IdentityVerificationResponse.from(v, ninCheck(userId)))
                .orElseGet(() -> IdentityVerificationResponse.notStarted(userId));
    }

    @Transactional(readOnly = true)
    public VerificationStatus statusOf(String userId) {
        return identityVerificationRepository.findByUserId(userId)
                .map(IdentityVerification::getStatus)
                .orElse(VerificationStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public PagedResponse<IdentityVerificationQueueItem> queue(UserRole callerRole, Pageable pageable) {
        requireOps(callerRole);
        Page<IdentityVerification> page = identityVerificationRepository.findByStatusOrderBySubmittedAtAsc(VerificationStatus.IN_REVIEW, pageable);
        List<String> userIds = page.getContent().stream().map(IdentityVerification::getUserId).toList();
        Map<String, Name> names = new HashMap<>();
        driverProfileRepository.findByUserIdIn(userIds).forEach(d -> names.put(d.getUserId(), new Name(d.getFirstName(), d.getLastName())));
        riderProfileRepository.findByUserIdIn(userIds).forEach(r -> names.putIfAbsent(r.getUserId(), new Name(r.getFirstName(), r.getLastName())));
        return PagedResponse.from(page.map(v -> {
            Name profile = names.get(v.getUserId());
            VerificationCheck check = latestCheck(v.getUserId());
            return new IdentityVerificationQueueItem(v.getUserId(),
                    profile == null ? null : profile.firstName(), profile == null ? null : profile.lastName(),
                    v.getNinLast4(), v.getSelfieUrl(), v.getSubmittedAt(), v.getAttempts(),
                    check == null ? null : check.getOutcome(), check == null ? null : check.getProviderSummary());
        }));
    }

    /** Approvals on a partial name match ops hasn't reviewed yet, oldest first. */
    @Transactional(readOnly = true)
    public PagedResponse<PartialNameMatchItem> partialNameMatches(UserRole callerRole, Pageable pageable) {
        requireOps(callerRole);
        Page<VerificationCheck> page = verificationCheckRepository
                .findByOutcomeAndReviewedAtIsNullOrderByCreatedAtAsc(VerificationCheckOutcome.PARTIAL_NAME_MATCH, pageable);
        List<String> userIds = page.getContent().stream().map(VerificationCheck::getDriverId).distinct().toList();
        Map<String, Name> names = new HashMap<>();
        driverProfileRepository.findByUserIdIn(userIds).forEach(d -> names.put(d.getUserId(), new Name(d.getFirstName(), d.getLastName())));
        riderProfileRepository.findByUserIdIn(userIds).forEach(r -> names.putIfAbsent(r.getUserId(), new Name(r.getFirstName(), r.getLastName())));
        return PagedResponse.from(page.map(c -> {
            Name profile = names.get(c.getDriverId());
            return new PartialNameMatchItem(c.getId(), c.getDriverId(),
                    profile == null ? null : profile.firstName(), profile == null ? null : profile.lastName(),
                    c.getCheckType(), c.getNameMatchScore(), c.getProviderSummary(), c.getCreatedAt());
        }));
    }

    /** Ops is satisfied it's the same person - it leaves the list. To undo the approval, reject instead. */
    @Transactional
    public void markPartialNameMatchReviewed(String checkId, UserRole callerRole, String callerId) {
        requireOps(callerRole);
        VerificationCheck check = verificationCheckRepository.findById(checkId)
                .filter(c -> c.getOutcome() == VerificationCheckOutcome.PARTIAL_NAME_MATCH)
                .orElseThrow(() -> new ResourceNotFoundException("No partial name match with that id"));
        if (check.getReviewedAt() == null) {
            check.setReviewedAt(Instant.now());
            check.setReviewedBy(callerId);
            verificationCheckRepository.save(check);
        }
    }

    private VerificationCheck latestCheck(String userId) {
        return verificationCheckRepository.findByDriverId(userId).stream()
                .filter(c -> c.getCheckType() == VerificationCheckType.NIN_FACE || c.getCheckType() == VerificationCheckType.NIN_LOOKUP)
                .max(Comparator.comparing(VerificationCheck::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    record Name(String firstName, String lastName) {
    }

    /**
     * A NIN Smile ID found: the NIMC name against their rider and driver profile names (NIMC sometimes holds them the
     * other way round - the best fit counts). Close enough approves; one name in common approves for ops to review
     * after (SMILE_ID_PARTIAL_NAME_MATCH); otherwise ops. confirmed ends the approval note.
     */
    SmileIdDecision matchNimcName(String userId, SmileIdResult.IdFields idFields, String confirmed) {
        String recordFirst = idFields == null ? null : SmileIdResults.blankToNull(idFields.firstName());
        String recordLast = idFields == null ? null : SmileIdResults.blankToNull(idFields.lastName());
        String fullName = idFields == null ? null : SmileIdResults.blankToNull(idFields.fullName());
        String onRecord = (fullName != null ? fullName : SmileIdResults.safe(recordFirst) + " " + SmileIdResults.safe(recordLast)).trim();
        // Sandbox checks are all sent as the test identity, so that's whose name comes back.
        List<Name> names = smileIdProperties.isSandbox()
                ? List.of(SmileIdResults.testIdentityName(smileIdProperties))
                : profileNames(userId);
        if (names.isEmpty() || recordFirst == null || recordLast == null) {
            return SmileIdDecision.ops(VerificationCheckOutcome.PROVIDER_ERROR, null, true,
                    "Smile ID cleared this check but the NIMC name couldn't be compared (NIMC: " + onRecord + ")");
        }
        Name best = names.get(0);
        double score = -1;
        for (Name name : names) {
            double s = NameMatcher.bestOrder(name.firstName(), name.lastName(), recordFirst, recordLast);
            if (s > score) {
                score = s;
                best = name;
            }
        }
        if (score < smileIdProperties.getNameMatchThreshold()) {
            String mismatch = "NIN is held by " + onRecord + ", profile says " + best.firstName() + " " + best.lastName();
            if (smileIdProperties.approvesPartialNames() && sharesAName(names, idFields)) {
                return SmileIdDecision.approveForReview(score, "Approved on a partial name match - review: " + mismatch + confirmed);
            }
            return SmileIdDecision.ops(VerificationCheckOutcome.NAME_MISMATCH, score, true, mismatch);
        }
        return SmileIdDecision.approve(score, "NIMC confirmed " + onRecord + confirmed);
    }

    /** Any of their profile names against any name on the record, middle names included. */
    boolean sharesAName(List<Name> names, SmileIdResult.IdFields idFields) {
        List<String> entered = names.stream().flatMap(n -> java.util.stream.Stream.of(n.firstName(), n.lastName())).toList();
        return NameMatcher.sharesAName(entered, recordNames(idFields), smileIdProperties.getNameMatchThreshold());
    }

    static List<String> recordNames(SmileIdResult.IdFields idFields) {
        return idFields == null ? List.of()
                : java.util.Arrays.asList(idFields.firstName(), idFields.otherNames(), idFields.lastName(), idFields.fullName());
    }

    /** Their rider and driver profile names, whichever exist - the result is matched against the closer one. */
    List<Name> profileNames(String userId) {
        List<Name> names = new ArrayList<>();
        riderProfileRepository.findByUserId(userId).map(r -> new Name(r.getFirstName(), r.getLastName()))
                .filter(n -> !isBlank(n.firstName()) && !isBlank(n.lastName())).ifPresent(names::add);
        driverProfileRepository.findByUserId(userId).map(d -> new Name(d.getFirstName(), d.getLastName()))
                .filter(n -> !isBlank(n.firstName()) && !isBlank(n.lastName())).ifPresent(names::add);
        return names;
    }

    private SmileIdCheck ninCheck(String userId) {
        return smileIdCheckRepository.findByUserIdAndCheckType(userId, SmileIdCheckType.NIN).orElse(null);
    }

    Name profileName(String userId, UserRole role) {
        return nameOf(userId, role).orElseThrow();
    }

    /** Riders and drivers alike - the name checked against the NIN is the one on their profile for the role they're in. */
    void requireProfileName(String userId, UserRole role) {
        Name profile = nameOf(userId, role)
                .orElseThrow(() -> new BadRequestException("Save your profile before verifying your identity"));
        if (isBlank(profile.firstName()) || isBlank(profile.lastName())) {
            throw new BadRequestException("Add your first and last name, as on your NIN, before verifying");
        }
    }

    /** The profile for the role they're in, or their other one - a person verifies once, whichever app they're in. */
    private Optional<Name> nameOf(String userId, UserRole role) {
        Optional<Name> rider = riderProfileRepository.findByUserId(userId).map(r -> new Name(r.getFirstName(), r.getLastName()));
        Optional<Name> driver = driverProfileRepository.findByUserId(userId).map(d -> new Name(d.getFirstName(), d.getLastName()));
        return role == UserRole.DRIVER ? driver.or(() -> rider) : rider.or(() -> driver);
    }

    /** Dev seeding only - the seeded accounts start verified so testing isn't blocked by the identity gate. */
    @Transactional
    public void approveForSeed(String userId) {
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId)
                .orElseGet(() -> IdentityVerification.builder().userId(userId).ninLast4("0000").build());
        if (verification.getStatus() != VerificationStatus.APPROVED) {
            decider.approve(verification, "SEED");
        }
    }

    private static void requireOps(UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can review rider identity checks");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
