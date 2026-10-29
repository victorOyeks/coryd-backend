package org.coryd.profile.service;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.client.SmileIdClient;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.DriverNinView;
import org.coryd.profile.dto.NinLookupResponse;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Starts a driver's NIN lookup. Every lookup is billed, so everything that can be answered without one is
 * answered here first, free: the number's format (by the request), a NIN already confirmed, a number NIMC
 * already said doesn't exist, a lookup still running, a retry too soon after the last, and tries used up.
 * Only then is Smile ID asked - and its answer comes to the webhook like every other check (NinLookupCheck).
 * A NIN already on another account is still looked up; the result's note tells ops which account.
 *
 * <p>Deliberately not one transaction: the check is saved before Smile ID is called, so a result that comes
 * back quickly always finds it.
 */
@Slf4j
@Service
public class NinLookupService {

    public static final String CHECKING = "CHECKING";
    public static final String MATCH = "MATCH";
    public static final String IN_REVIEW = "IN_REVIEW";
    public static final String PENDING = "PENDING";
    public static final String REJECTED = "REJECTED";

    private final NinLookupCheck check;
    private final SmileIdCheckRepository smileIdCheckRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final DriverIdentity driverIdentity;
    private final SmileIdClient smileIdClient;
    private final IdentityServiceClient identityServiceClient;
    private final SmileIdProperties properties;

    NinLookupService(NinLookupCheck check, SmileIdCheckRepository smileIdCheckRepository, DriverProfileRepository driverProfileRepository,
                     IdentityVerificationRepository identityVerificationRepository, DriverIdentity driverIdentity,
                     SmileIdClient smileIdClient, IdentityServiceClient identityServiceClient, SmileIdProperties properties) {
        this.check = check;
        this.smileIdCheckRepository = smileIdCheckRepository;
        this.driverProfileRepository = driverProfileRepository;
        this.identityVerificationRepository = identityVerificationRepository;
        this.driverIdentity = driverIdentity;
        this.smileIdClient = smileIdClient;
        this.identityServiceClient = identityServiceClient;
        this.properties = properties;
    }

    public NinLookupResponse start(String userId, String nin) {
        check.validateStart(userId, UserRole.DRIVER);
        boolean sandbox = properties.isSandbox();
        SmileIdCheck lookup = smileIdCheckRepository.findByUserIdAndCheckType(userId, SmileIdCheckType.NIN_LOOKUP)
                .orElseGet(() -> SmileIdCheck.builder().userId(userId).checkType(SmileIdCheckType.NIN_LOOKUP).build());
        if (lookup.waitingForResult()) {
            return new NinLookupResponse(CHECKING, last4(userId));
        }
        if (!sandbox && lookup.getAttempts() >= properties.getNinLookupMaxAttempts()) {
            throw new BadRequestException("You've used all your NIN tries - contact CoRyd support to finish checking your NIN");
        }
        if (!sandbox && lookup.getUpdatedAt() != null
                && lookup.getUpdatedAt().isAfter(Instant.now().minus(Duration.ofSeconds(properties.getNinLookupCooldownSeconds())))) {
            throw new BadRequestException("Give it a few seconds before trying again");
        }
        String fingerprint = driverIdentity.fingerprint(nin);
        if (fingerprint == null) {
            throw new BadRequestException("NIN checks are unavailable right now - try again later");
        }
        if (!sandbox && driverIdentity.knownMiss(userId, fingerprint)) {
            throw new BadRequestException("NIMC had no record of that NIN when you tried it - check the number and try again");
        }
        if (!properties.isConfigured()) {
            log.error("NIN lookup requested by {} but Smile ID isn't configured (SMILE_ID_PARTNER_ID / SMILE_ID_API_KEY)", userId);
            throw new BadRequestException("NIN checks are unavailable right now - try again later");
        }

        check.opened(userId, nin, fingerprint);

        String checkId = "coryd-ninl-" + UUID.randomUUID();
        lookup.setCheckId(checkId);
        lookup.setStartedAt(Instant.now());
        lookup.setRetryReason(null);
        smileIdCheckRepository.save(lookup);

        DriverProfile driver = driverProfileRepository.findByUserId(userId).orElseThrow();
        SmileIdClient.NinLookup request = sandbox
                // Sandbox only answers for its test identity - every lookup is sent as it, so every one succeeds.
                ? new SmileIdClient.NinLookup(checkId, properties.getIdType(), nin, properties.getTestIdentity().getGivenNames(),
                        properties.getTestIdentity().getLastName(), properties.getTestIdentity().getEmail(), null, Instant.now())
                : new SmileIdClient.NinLookup(checkId, properties.getIdType(), nin, driver.getFirstName(), driver.getLastName(),
                        blankToNull(driver.getEmail()), e164(identityServiceClient.fetchPhone(userId)), Instant.now());
        try {
            smileIdClient.submitNinLookup(request);
        } catch (RuntimeException e) {
            lookup.setStartedAt(null);
            smileIdCheckRepository.save(lookup);
            throw e;
        }
        log.info("NIN lookup {} sent for driver {}", checkId, userId);
        return new NinLookupResponse(CHECKING, nin.substring(7));
    }

    /** What GET /drivers/me shows about the NIN step. */
    public DriverNinView view(DriverProfile driver) {
        String userId = driver.getUserId();
        Optional<IdentityVerification> identity = identityVerificationRepository.findByUserId(userId);
        SmileIdCheck lookup = smileIdCheckRepository.findByUserIdAndCheckType(userId, SmileIdCheckType.NIN_LOOKUP).orElse(null);
        String last4 = identity.map(IdentityVerification::getNinLast4).orElse(null);
        int triesLeft = lookup == null ? properties.getNinLookupMaxAttempts()
                : Math.max(0, properties.getNinLookupMaxAttempts() - lookup.getAttempts());
        boolean verified = identity.map(v -> v.getStatus() == VerificationStatus.APPROVED).orElse(false);
        String status;
        if (verified || driver.getNinStatus() == VerificationStatus.APPROVED) {
            status = MATCH;
        } else if (lookup != null && lookup.waitingForResult()) {
            status = CHECKING;
        } else if (driver.getNinStatus() == VerificationStatus.IN_REVIEW) {
            status = IN_REVIEW;
        } else if (driver.getNinStatus() == VerificationStatus.REJECTED) {
            status = REJECTED;
        } else {
            status = PENDING;
        }
        return new DriverNinView(properties.driversLookUpNin(), status, last4,
                PENDING.equals(status) && lookup != null ? lookup.getRetryReason() : null,
                properties.isSandbox() ? properties.getNinLookupMaxAttempts() : triesLeft);
    }


    private String last4(String userId) {
        return identityVerificationRepository.findByUserId(userId).map(IdentityVerification::getNinLast4).orElse(null);
    }

    /** 08012345678 or 2348012345678 -> +2348012345678; anything else is left out (Smile ID also takes an email). */
    static String e164(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) {
            return digits;
        }
        if (digits.startsWith("234") && digits.length() == 13) {
            return "+" + digits;
        }
        if (digits.startsWith("0") && digits.length() == 11) {
            return "+234" + digits.substring(1);
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
