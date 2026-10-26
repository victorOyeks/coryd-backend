package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * A driver's NIN looked up with NIMC by number only (Enhanced KYC) - no selfie, since their licence check
 * matches their face. The lookup's own outcome is driver_profiles.nin_status; the driver's identity is
 * verified once this and the licence have both passed (DriverIdentity).
 */
@Component
@RequiredArgsConstructor
class NinLookupCheck implements SmileIdCheckHandler {

    private final DriverProfileRepository driverProfileRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final IdentityVerificationService identityVerificationService;
    private final VerifiedIdentityService verifiedIdentityService;
    private final DriverIdentity driverIdentity;
    private final SmileIdProperties properties;

    @Override
    public SmileIdCheckType type() {
        return SmileIdCheckType.NIN_LOOKUP;
    }

    @Override
    public String idType() {
        return properties.getIdType();
    }

    @Override
    public VerificationCheckType recordedAs() {
        return VerificationCheckType.NIN_LOOKUP;
    }

    @Override
    public String noun() {
        return "NIN";
    }

    @Override
    public void validateStart(String userId, UserRole role) {
        if (!properties.driversLookUpNin()) {
            throw new BadRequestException("Verify your identity with the NIN and selfie check");
        }
        DriverProfile driver = driverProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Save your driver profile before checking your NIN"));
        identityVerificationService.requireProfileName(userId, UserRole.DRIVER);
        if (properties.isSandbox()) {
            return; // dev: look up as often as you like, whatever the status
        }
        VerificationStatus identity = identityVerificationRepository.findByUserId(userId)
                .map(IdentityVerification::getStatus).orElse(VerificationStatus.PENDING);
        if (identity == VerificationStatus.APPROVED || driver.getNinStatus() == VerificationStatus.APPROVED) {
            throw new BadRequestException("Your NIN is already confirmed");
        }
        if (driver.getNinStatus() == VerificationStatus.IN_REVIEW || identity == VerificationStatus.IN_REVIEW) {
            throw new BadRequestException("Your NIN is with our team - we'll let you know once it's checked");
        }
    }

    /** nin is the full NIN - only its last four digits and its fingerprint are kept. */
    void opened(String userId, String nin, String fingerprint) {
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId)
                .orElseGet(() -> IdentityVerification.builder().userId(userId).build());
        Instant now = Instant.now();
        if (verification.getStatus() != VerificationStatus.APPROVED) {
            verification.setStatus(VerificationStatus.PENDING);
        }
        verification.setNinLast4(nin.substring(nin.length() - 4));
        verification.setNinFingerprint(fingerprint);
        verification.setConsentAt(now);
        verification.setSubmittedAt(now);
        verification.setRejectionReason(null);
        identityVerificationRepository.save(verification);
        driverProfileRepository.findByUserId(userId).ifPresent(driver -> {
            if (driver.getNinStatus() != VerificationStatus.APPROVED) {
                driver.setNinStatus(VerificationStatus.PENDING);
                driverProfileRepository.save(driver);
            }
        });
    }

    @Override
    public void opened(String userId, String nin) {
        throw new UnsupportedOperationException("NIN lookups are opened by NinLookupService");
    }

    @Override
    public IdentityVerificationService.Name profileName(String userId, UserRole role) {
        return identityVerificationService.profileName(userId, UserRole.DRIVER);
    }

    @Override
    public boolean awaitingResult(String userId) {
        return driverProfileRepository.findByUserId(userId)
                .map(d -> d.getNinStatus() == VerificationStatus.PENDING
                        || (properties.isSandbox() && d.getNinStatus() == VerificationStatus.APPROVED))
                .orElse(false);
    }

    @Override
    public SmileIdDecision onClear(String userId, SmileIdResult.IdFields idFields) {
        return identityVerificationService.matchNimcName(userId, idFields, " (NIN lookup - the face is matched on the licence)");
    }

    @Override
    public SmileIdDecision onReason(String status, String reason, String message) {
        if ("block".equals(status) && "identifier_not_found".equals(reason)) {
            return SmileIdDecision.retry(SmileIdRetryReason.NIN_NOT_FOUND, VerificationCheckOutcome.NOT_FOUND, true,
                    "NIMC has no record for this NIN");
        }
        return null;
    }

    /** A NIN NIMC doesn't know is remembered, so the same number isn't paid for twice. */
    @Override
    public void afterResult(String userId, SmileIdDecision decision) {
        if (decision.retryReason() == SmileIdRetryReason.NIN_NOT_FOUND && !properties.isSandbox()) {
            driverIdentity.recordMiss(userId);
        }
    }

    @Override
    public void approve(String userId, SmileIdResult.IdFields idFields) {
        driverProfileRepository.findByUserId(userId).ifPresent(driver -> {
            driver.setNinStatus(VerificationStatus.APPROVED);
            driverProfileRepository.save(driver);
        });
        verifiedIdentityService.applyFromNimc(userId, idFields);
        driverIdentity.completeIfReady(userId);
    }

    @Override
    public void sendToOps(String userId) {
        driverProfileRepository.findByUserId(userId).ifPresent(driver -> {
            driver.setNinStatus(VerificationStatus.IN_REVIEW);
            driverProfileRepository.save(driver);
        });
        identityVerificationRepository.findByUserId(userId).ifPresent(v -> {
            if (v.getStatus() != VerificationStatus.APPROVED) {
                v.setStatus(VerificationStatus.IN_REVIEW);
                identityVerificationRepository.save(v);
            }
        });
    }
}
