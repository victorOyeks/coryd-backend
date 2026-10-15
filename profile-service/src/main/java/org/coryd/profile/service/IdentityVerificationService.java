package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.dto.IdentityVerificationQueueItem;
import org.coryd.profile.dto.IdentityVerificationResponse;
import org.coryd.profile.dto.SubmitIdentityVerificationRequest;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Rider identity check during onboarding: NIN + selfie. Prembly matches what it can on its own; anything
 * it can't (or everything, while it's switched off) waits in the ops queue. A rider is never rejected by
 * a machine - only an ops decision rejects, with a reason the rider sees.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityVerificationService {

    /** A sixth attempt goes through support - repeated tries with different NINs are worth a human look. */
    static final int MAX_ATTEMPTS = 5;

    private final IdentityVerificationRepository identityVerificationRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final VerificationCheckRepository verificationCheckRepository;
    private final PremblyVerificationService premblyVerificationService;
    private final IdentityVerificationDecider decider;

    /** Riders and drivers alike - the name checked against the NIN is the one on the caller's own profile for the role they're in. */
    @Transactional
    public IdentityVerificationResponse submit(String userId, UserRole role, SubmitIdentityVerificationRequest request) {
        Name profile = nameOf(userId, role)
                .orElseThrow(() -> new BadRequestException("Save your profile before verifying your identity"));
        if (isBlank(profile.firstName()) || isBlank(profile.lastName())) {
            throw new BadRequestException("Add your first and last name, as on your NIN, before verifying");
        }
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
        IdentityVerification saved = identityVerificationRepository.save(verification);

        // After commit: the check updates this row, which must be visible to it.
        String nin = request.nin();
        String first = profile.firstName();
        String last = profile.lastName();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                premblyVerificationService.checkNin(userId, nin, request.selfieUrl(), request.selfiePublicId(), first, last);
            }
        });
        return IdentityVerificationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public IdentityVerificationResponse mine(String userId) {
        return identityVerificationRepository.findByUserId(userId)
                .map(IdentityVerificationResponse::from)
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

    @Transactional
    public IdentityVerificationResponse decide(String riderId, UserRole callerRole, String callerId, boolean approve, String reason) {
        requireOps(callerRole);
        IdentityVerification verification = identityVerificationRepository.findByUserId(riderId)
                .orElseThrow(() -> new ResourceNotFoundException("This rider hasn't submitted their identity yet"));
        if (approve) {
            decider.approve(verification, callerId);
        } else {
            if (isBlank(reason)) {
                throw new BadRequestException("Give the rider a reason so they can fix it");
            }
            decider.reject(verification, reason.trim(), callerId);
        }
        return IdentityVerificationResponse.from(verification);
    }

    private VerificationCheck latestCheck(String userId) {
        return verificationCheckRepository.findByDriverId(userId).stream()
                .filter(c -> c.getCheckType() == VerificationCheckType.NIN_FACE)
                .max(Comparator.comparing(VerificationCheck::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private record Name(String firstName, String lastName) {
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
