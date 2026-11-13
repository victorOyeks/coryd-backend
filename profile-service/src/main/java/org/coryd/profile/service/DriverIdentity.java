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
import java.util.List;
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
    private static final String CROSS_CHECK_PREFIX = "cross-check-";
    /** Approved although NIN and licence share only some names - for ops to review, never holding the driver back. */
    private static final String PARTIAL_CROSS_CHECK_PREFIX = "partial-cross-check-";

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
        // An IN_REVIEW left from an earlier NIN + selfie attempt doesn't hold them back - only a mismatch ops hasn't decided
        if (verification == null || driver == null || verification.getStatus() == VerificationStatus.APPROVED
                || (verification.getStatus() == VerificationStatus.IN_REVIEW && crossCheckFlagged(userId))
                || driver.getNinStatus() != VerificationStatus.APPROVED
                || driver.getGovernmentIdStatus() != VerificationStatus.APPROVED) {
            return;
        }
        CrossCheck crossCheck = properties.isSandbox() ? CrossCheck.AGREE : licenceMismatch(verification, userId);
        String mismatch = crossCheck.mismatch();
        if (mismatch != null) {
            verification.setStatus(VerificationStatus.IN_REVIEW);
            identityVerificationRepository.save(verification);
            verificationCheckRepository.save(VerificationCheck.builder()
                    .driverId(userId)
                    .checkType(VerificationCheckType.NIN_LOOKUP)
                    .subjectKey(CROSS_CHECK_PREFIX + UUID.randomUUID())
                    .outcome(VerificationCheckOutcome.NAME_MISMATCH)
                    .providerSummary(SmileIdResults.truncate("NIN and licence don't agree: " + mismatch))
                    .build());
            log.info("Driver {} NIN and licence don't agree - sent to ops: {}", userId, mismatch);
            return;
        }
        if (crossCheck.partial() != null) {
            verificationCheckRepository.save(VerificationCheck.builder()
                    .driverId(userId)
                    .checkType(VerificationCheckType.NIN_LOOKUP)
                    .subjectKey(PARTIAL_CROSS_CHECK_PREFIX + UUID.randomUUID())
                    .outcome(VerificationCheckOutcome.PARTIAL_NAME_MATCH)
                    .providerSummary(SmileIdResults.truncate("Approved on a partial name match - review: NIN and licence names differ: "
                            + crossCheck.partial()))
                    .build());
            log.info("Driver {} NIN and licence share only some names - approved, flagged for ops: {}", userId, crossCheck.partial());
        }
        decider.approve(verification, DECIDED_BY);
        log.info("Driver {} identity verified by NIN lookup and licence", userId);
    }

    /**
     * The one rule for "is this driver verified as a person", read by trip-service for both posting a route and
     * posting-eligibility: their identity check is approved, or - in the lookup flow - the NIN is confirmed and the
     * licence approved, unless the two records disagreed and ops hasn't approved them since.
     */
    public boolean verified(String userId) {
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId).orElse(null);
        if (verification != null && verification.getStatus() == VerificationStatus.APPROVED) {
            return true;
        }
        if (!properties.driversLookUpNin() || verification == null) {
            return false;
        }
        DriverProfile driver = driverProfileRepository.findByUserId(userId).orElse(null);
        if (driver == null || driver.getNinStatus() != VerificationStatus.APPROVED
                || driver.getGovernmentIdStatus() != VerificationStatus.APPROVED) {
            return false;
        }
        boolean opsHasIt = verification.getStatus() == VerificationStatus.IN_REVIEW
                || verification.getStatus() == VerificationStatus.REJECTED;
        return !(opsHasIt && crossCheckFlagged(userId));
    }

    /** completeIfReady once found the NIN and licence of different people. */
    private boolean crossCheckFlagged(String userId) {
        return verificationCheckRepository.findByDriverId(userId).stream()
                .anyMatch(c -> c.getCheckType() == VerificationCheckType.NIN_LOOKUP
                        && c.getSubjectKey() != null && c.getSubjectKey().startsWith(CROSS_CHECK_PREFIX));
    }

    /** mismatch sends the driver to ops; partial (names that only partly agree) approves them and flags it for ops. */
    private record CrossCheck(String mismatch, String partial) {
        static final CrossCheck AGREE = new CrossCheck(null, null);
    }

    /** The NIMC record from the lookup against the licence Smile ID read - AGREE when they do, or there's nothing to compare. */
    private CrossCheck licenceMismatch(IdentityVerification nimc, String userId) {
        SmileIdResult.IdFields licence = latestLicence(userId).orElse(null);
        if (licence == null || nimc.getVerifiedFirstName() == null || nimc.getVerifiedLastName() == null
                || licence.firstName() == null || licence.lastName() == null) {
            return CrossCheck.AGREE; // a licence approved by ops from a photo, before Smile ID - ops already compared it by eye
        }
        String partial = null;
        double score = NameMatcher.bestOrder(nimc.getVerifiedFirstName(), nimc.getVerifiedLastName(), licence.firstName(), licence.lastName());
        if (score < properties.getNameMatchThreshold()) {
            String names = "NIMC has " + nimc.getVerifiedFirstName() + " " + nimc.getVerifiedLastName()
                    + ", the licence has " + licence.firstName() + " " + licence.lastName();
            boolean sharesAName = NameMatcher.sharesAName(List.of(nimc.getVerifiedFirstName(), nimc.getVerifiedLastName()),
                    IdentityVerificationService.recordNames(licence), properties.getNameMatchThreshold());
            if (!properties.approvesPartialNames() || !sharesAName) {
                return new CrossCheck(names, null);
            }
            partial = names;
        }
        LocalDate licenceDob = VerifiedIdentityService.date(licence.dateOfBirth());
        if (nimc.getVerifiedDateOfBirth() != null && licenceDob != null && !nimc.getVerifiedDateOfBirth().equals(licenceDob)) {
            // Shown in the ops console, so years only - the full dates are an audited reveal (docs/ops-console/privacy.md).
            return new CrossCheck("NIMC and licence dates of birth differ (born " + nimc.getVerifiedDateOfBirth().getYear() + " / "
                    + licenceDob.getYear() + ")", null);
        }
        return new CrossCheck(null, partial);
    }

    private Optional<SmileIdResult.IdFields> latestLicence(String userId) {
        return verificationCheckRepository.findByDriverId(userId).stream()
                .filter(c -> c.getCheckType() == VerificationCheckType.DRIVERS_LICENCE && c.getRawResponse() != null
                        && (c.getOutcome() == VerificationCheckOutcome.MATCH || c.getOutcome() == VerificationCheckOutcome.PARTIAL_NAME_MATCH))
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
