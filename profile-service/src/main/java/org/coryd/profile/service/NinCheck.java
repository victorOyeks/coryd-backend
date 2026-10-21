package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static org.coryd.profile.service.SmileIdResults.blankToNull;
import static org.coryd.profile.service.SmileIdResults.safe;

/**
 * The NIN check everyone does (Biometric KYC: NIN looked up with NIMC, live selfie matched to the NIN
 * photo). Its outcome lives on identity_verifications; approval goes through IdentityVerificationDecider,
 * which tells trip-service.
 */
@Component
@RequiredArgsConstructor
class NinCheck implements SmileIdCheckHandler {

    private final IdentityVerificationRepository identityVerificationRepository;
    private final IdentityVerificationService identityVerificationService;
    private final IdentityVerificationDecider decider;
    private final SmileIdProperties properties;

    @Override
    public SmileIdCheckType type() {
        return SmileIdCheckType.NIN;
    }

    @Override
    public String idType() {
        return properties.getIdType();
    }

    @Override
    public VerificationCheckType recordedAs() {
        return VerificationCheckType.NIN_FACE;
    }

    @Override
    public String noun() {
        return "identity";
    }

    @Override
    public void validateStart(String userId, UserRole role) {
        identityVerificationService.requireProfileName(userId, role);
        if (properties.isSandbox()) {
            return; // dev: re-run the check as often as you like, whatever the person's status
        }
        VerificationStatus status = statusOf(userId);
        if (status == VerificationStatus.APPROVED) {
            throw new BadRequestException("Your identity is already verified");
        }
        if (status == VerificationStatus.IN_REVIEW) {
            throw new BadRequestException("Your identity check is with our team - we'll let you know once it's done");
        }
    }

    @Override
    public void opened(String userId, String nin) {
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId)
                .orElseGet(() -> IdentityVerification.builder().userId(userId).build());
        Instant now = Instant.now();
        // A sandbox re-check of someone already verified leaves them verified until its result lands.
        if (!(properties.isSandbox() && verification.getStatus() == VerificationStatus.APPROVED)) {
            verification.setStatus(VerificationStatus.PENDING);
        }
        verification.setNinLast4(nin.substring(nin.length() - 4));
        verification.setConsentAt(now);
        verification.setSubmittedAt(now);
        verification.setRejectionReason(null);
        identityVerificationRepository.save(verification);
    }

    @Override
    public IdentityVerificationService.Name profileName(String userId, UserRole role) {
        return identityVerificationService.profileName(userId, role);
    }

    @Override
    public boolean awaitingResult(String userId) {
        VerificationStatus status = statusOf(userId);
        return status == VerificationStatus.PENDING || (properties.isSandbox() && status == VerificationStatus.APPROVED);
    }

    @Override
    public SmileIdDecision onClear(String userId, SmileIdResult.IdFields idFields) {
        String recordFirst = idFields == null ? null : blankToNull(idFields.firstName());
        String recordLast = idFields == null ? null : blankToNull(idFields.lastName());
        String fullName = idFields == null ? null : blankToNull(idFields.fullName());
        String onRecord = (fullName != null ? fullName : safe(recordFirst) + " " + safe(recordLast)).trim();
        // Sandbox checks are all sent as the test identity, so that's whose name comes back.
        List<IdentityVerificationService.Name> names = properties.isSandbox()
                ? List.of(SmileIdResults.testIdentityName(properties))
                : identityVerificationService.profileNames(userId);
        if (names.isEmpty() || recordFirst == null || recordLast == null) {
            return SmileIdDecision.ops(VerificationCheckOutcome.PROVIDER_ERROR, null, true,
                    "Smile ID cleared this check but the NIMC name couldn't be compared (NIMC: " + onRecord + ")");
        }
        // Their rider or driver profile, and NIMC sometimes holds the names the other way round - the best fit counts.
        IdentityVerificationService.Name best = names.get(0);
        double score = -1;
        for (IdentityVerificationService.Name name : names) {
            double s = NameMatcher.bestOrder(name.firstName(), name.lastName(), recordFirst, recordLast);
            if (s > score) {
                score = s;
                best = name;
            }
        }
        if (score < properties.getNameMatchThreshold()) {
            return SmileIdDecision.ops(VerificationCheckOutcome.NAME_MISMATCH, score, true,
                    "NIN is held by " + onRecord + ", profile says " + best.firstName() + " " + best.lastName());
        }
        return SmileIdDecision.approve(score, "NIMC confirmed " + onRecord + ", live selfie matched the NIN photo");
    }

    @Override
    public SmileIdDecision onReason(String status, String reason, String message) {
        if ("block".equals(status) && "identifier_not_found".equals(reason)) {
            return SmileIdDecision.retry(SmileIdRetryReason.NIN_NOT_FOUND, VerificationCheckOutcome.NOT_FOUND, true,
                    "NIMC has no record for this NIN");
        }
        if ("error".equals(status) && "image_unavailable_or_invalid".equals(reason)
                && message != null && SmileIdResults.lower(message).contains("authority photo")) {
            // NIMC has no photo for this NIN - another selfie will never match, so a person has to look.
            return SmileIdDecision.ops(VerificationCheckOutcome.PROVIDER_ERROR, null, true,
                    "NIMC has no photo for this NIN, so the selfie couldn't be compared");
        }
        return null;
    }

    @Override
    public void approve(String userId) {
        identityVerificationRepository.findByUserId(userId)
                .ifPresent(v -> decider.approve(v, IdentityVerificationDecider.SMILE_ID));
    }

    @Override
    public void sendToOps(String userId) {
        identityVerificationRepository.findByUserId(userId).ifPresent(v -> {
            v.setStatus(VerificationStatus.IN_REVIEW);
            identityVerificationRepository.save(v);
        });
    }

    private VerificationStatus statusOf(String userId) {
        return identityVerificationRepository.findByUserId(userId)
                .map(IdentityVerification::getStatus)
                .orElse(VerificationStatus.PENDING);
    }
}
