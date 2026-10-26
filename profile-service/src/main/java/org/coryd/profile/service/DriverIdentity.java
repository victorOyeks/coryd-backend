package org.coryd.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * A driver's identity, when they look their NIN up by number instead of doing the NIN + selfie check:
 * verified once the lookup and the licence check have both passed and the two records are of the same
 * person - the NIMC name (and date of birth, where both give one) matching the licence. Approval goes
 * through the same IdentityVerificationDecider as every other identity check, so trip-service, onboarding
 * and the name lock all follow. Someone already verified with the NIN + selfie check is never touched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriverIdentity {

    /** decidedBy for a driver verified by their NIN lookup and licence together. */
    static final String DECIDED_BY = "SMILE_ID_NIN_LOOKUP_AND_LICENCE";

    private final DriverProfileRepository driverProfileRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final VerificationCheckRepository verificationCheckRepository;
    private final IdentityVerificationDecider decider;
    private final SmileIdProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    /** Called whenever either half lands - the lookup approved, or the licence approved (by Smile ID or ops). */
    public void completeIfReady(String userId) {
        if (!properties.driversLookUpNin()) {
            return;
        }
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId).orElse(null);
        DriverProfile driver = driverProfileRepository.findByUserId(userId).orElse(null);
        if (verification == null || driver == null || verification.getStatus() == VerificationStatus.APPROVED
                || verification.getStatus() == VerificationStatus.IN_REVIEW
                || driver.getNinStatus() != VerificationStatus.APPROVED
                || driver.getGovernmentIdStatus() != VerificationStatus.APPROVED) {
            return;
        }
        String mismatch = properties.isSandbox() ? null : licenceMismatch(verification, userId);
        if (mismatch != null) {
            verification.setStatus(VerificationStatus.IN_REVIEW);
            identityVerificationRepository.save(verification);
            verificationCheckRepository.save(VerificationCheck.builder()
                    .driverId(userId)
                    .checkType(VerificationCheckType.NIN_LOOKUP)
                    .subjectKey("cross-check-" + UUID.randomUUID())
                    .outcome(VerificationCheckOutcome.NAME_MISMATCH)
                    .providerSummary(SmileIdResults.truncate("NIN and licence don't agree: " + mismatch))
                    .build());
            log.info("Driver {} NIN and licence don't agree - sent to ops: {}", userId, mismatch);
            return;
        }
        decider.approve(verification, DECIDED_BY);
        log.info("Driver {} identity verified by NIN lookup and licence", userId);
    }

    /** The NIMC record from the lookup against the licence Smile ID read - null when they agree, or there's nothing to compare. */
    private String licenceMismatch(IdentityVerification nimc, String userId) {
        SmileIdResult.IdFields licence = latestLicence(userId).orElse(null);
        if (licence == null || nimc.getVerifiedFirstName() == null || nimc.getVerifiedLastName() == null
                || licence.firstName() == null || licence.lastName() == null) {
            return null; // a licence approved by ops from a photo, before Smile ID - ops already compared it by eye
        }
        double score = NameMatcher.bestOrder(nimc.getVerifiedFirstName(), nimc.getVerifiedLastName(), licence.firstName(), licence.lastName());
        if (score < properties.getNameMatchThreshold()) {
            return "NIMC has " + nimc.getVerifiedFirstName() + " " + nimc.getVerifiedLastName()
                    + ", the licence has " + licence.firstName() + " " + licence.lastName();
        }
        LocalDate licenceDob = VerifiedIdentityService.date(licence.dateOfBirth());
        if (nimc.getVerifiedDateOfBirth() != null && licenceDob != null && !nimc.getVerifiedDateOfBirth().equals(licenceDob)) {
            return "NIMC date of birth " + nimc.getVerifiedDateOfBirth() + ", licence " + licenceDob;
        }
        return null;
    }

    private Optional<SmileIdResult.IdFields> latestLicence(String userId) {
        return verificationCheckRepository.findByDriverId(userId).stream()
                .filter(c -> c.getCheckType() == VerificationCheckType.DRIVERS_LICENCE && c.getRawResponse() != null
                        && c.getOutcome() == VerificationCheckOutcome.MATCH)
                .max(Comparator.comparing(VerificationCheck::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .flatMap(c -> {
                    try {
                        return Optional.ofNullable(objectMapper.readValue(c.getRawResponse(), SmileIdResult.class).idFields());
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                });
    }

    /** Keyed one-way fingerprint of a NIN - the same NIN always gives the same value, and the NIN can't be read back.
     * Null when NIN_FINGERPRINT_KEY isn't set: riders' checks carry on without one; driver lookups refuse to start. */
    String fingerprint(String nin) {
        if (properties.getNinFingerprintKey() == null || properties.getNinFingerprintKey().isBlank()) {
            log.error("NIN_FINGERPRINT_KEY isn't set - NINs can't be fingerprinted, so driver NIN lookups are off");
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getNinFingerprintKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(nin.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("NIN fingerprint unavailable", e);
        }
    }

    /** Another account already gave this NIN - its user ID, for ops. */
    Optional<String> usedElsewhere(String userId, String fingerprint) {
        return identityVerificationRepository.findByNinFingerprintAndUserIdNot(fingerprint, userId).stream()
                .map(IdentityVerification::getUserId).findFirst();
    }

    /** NIMC already said this person's NIN doesn't exist - looking it up again would be billed for the same answer. */
    boolean knownMiss(String userId, String fingerprint) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM profile.nin_lookup_misses WHERE user_id = ? AND fingerprint = ?",
                Integer.class, userId, fingerprint);
        return n != null && n > 0;
    }

    void recordMiss(String userId) {
        identityVerificationRepository.findByUserId(userId).map(IdentityVerification::getNinFingerprint).ifPresent(fp ->
                jdbc.update("INSERT INTO profile.nin_lookup_misses (user_id, fingerprint) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, fp));
    }
}
