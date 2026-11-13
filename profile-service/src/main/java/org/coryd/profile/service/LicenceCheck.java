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
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.coryd.profile.service.SmileIdResults.blankToNull;
import static org.coryd.profile.service.SmileIdResults.safe;

/**
 * A driver's licence (Document Verification: Smile ID confirms the licence is genuine, reads it, and
 * matches a live selfie to the photo on it - not an FRSC lookup, which Smile ID can't do in Nigeria).
 * Its outcome is the GOVERNMENT_ID item on driver_profiles; approval goes through the same path as an
 * ops approval, so the driver is notified and goes live if it was the last item.
 */
@Component
@RequiredArgsConstructor
class LicenceCheck implements SmileIdCheckHandler {

    static final String DECIDED_BY = "SMILE_ID";
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final DriverProfileRepository driverProfileRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final VerificationAggregationService verificationAggregationService;
    private final SmileIdProperties properties;

    @Override
    public SmileIdCheckType type() {
        return SmileIdCheckType.DRIVERS_LICENCE;
    }

    @Override
    public String idType() {
        return "DRIVERS_LICENSE";
    }

    @Override
    public VerificationCheckType recordedAs() {
        return VerificationCheckType.DRIVERS_LICENCE;
    }

    @Override
    public String noun() {
        return "licence";
    }

    @Override
    public void validateStart(String userId, UserRole role) {
        DriverProfile profile = driverProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Save your driver profile before verifying your licence"));
        if (isBlank(profile.getFirstName()) || isBlank(profile.getLastName()) || isBlank(profile.getIdNumber())) {
            throw new BadRequestException("Add your name and licence number, as on your licence, before verifying it");
        }
        // The NIN lookup is the cheaper check, so it goes first - a NIN that isn't real never costs a licence check.
        boolean identityVerified = identityVerificationRepository.findByUserId(userId)
                .map(v -> v.getStatus() == VerificationStatus.APPROVED).orElse(false);
        if (properties.driversLookUpNin() && !identityVerified && profile.getNinStatus() != VerificationStatus.APPROVED
                && profile.getNinStatus() != VerificationStatus.IN_REVIEW) {
            throw new BadRequestException("Confirm your NIN first - it's the step before your licence");
        }
        if (properties.isSandbox()) {
            return; // dev: re-run the check as often as you like, whatever the licence's status
        }
        if (profile.getGovernmentIdStatus() == VerificationStatus.APPROVED) {
            throw new BadRequestException("Your licence is already verified");
        }
        if (profile.getGovernmentIdStatus() == VerificationStatus.IN_REVIEW) {
            throw new BadRequestException("Your licence is with our team - we'll let you know once it's checked");
        }
    }

    /** A licence ops rejected goes back to PENDING for the new check, marked resubmitted in case it lands with ops again. */
    @Override
    public void opened(String userId, String nin) {
        driverProfileRepository.findByUserId(userId).ifPresent(profile -> {
            if (properties.isSandbox() && profile.getGovernmentIdStatus() == VerificationStatus.IN_REVIEW) {
                // dev re-check of a licence sitting with ops - take it back so the result can land
                profile.setGovernmentIdStatus(VerificationStatus.PENDING);
                driverProfileRepository.save(profile);
            }
            if (profile.getGovernmentIdStatus() == VerificationStatus.REJECTED) {
                profile.setGovernmentIdStatus(VerificationStatus.PENDING);
                profile.setGovernmentIdResubmitted(true);
                profile.setGovernmentIdRejectionReason(null);
                driverProfileRepository.save(profile);
            }
            if (profile.isGovernmentIdDecidedDuringCheck()) {
                // A new check replaces the old one - nothing left for a late result to override.
                profile.setGovernmentIdDecidedDuringCheck(false);
                driverProfileRepository.save(profile);
            }
        });
    }

    @Override
    public IdentityVerificationService.Name profileName(String userId, UserRole role) {
        DriverProfile profile = driverProfileRepository.findByUserId(userId).orElseThrow();
        return new IdentityVerificationService.Name(profile.getFirstName(), profile.getLastName());
    }

    @Override
    public boolean awaitingResult(String userId) {
        return driverProfileRepository.findByUserId(userId)
                .map(p -> p.getGovernmentIdStatus() == VerificationStatus.PENDING
                        || p.isGovernmentIdDecidedDuringCheck()
                        || (properties.isSandbox() && p.getGovernmentIdStatus() == VerificationStatus.APPROVED))
                .orElse(false);
    }

    /** Ops approved or rejected this licence before Smile ID answered - ops' decision stands when the answer comes. */
    @Override
    public boolean opsDecidedWhileOut(String userId) {
        return driverProfileRepository.findByUserId(userId).map(DriverProfile::isGovernmentIdDecidedDuringCheck).orElse(false);
    }

    /**
     * Smile ID's answer to a licence ops already decided: recorded in the audit log as PROVIDER_RESULT with what Smile ID
     * said, and nothing else changes - not the licence, not the driver's posting, not their tries. Ops can act on it from
     * the history if it disagrees with them.
     */
    @Override
    public void recordLateResult(String userId, SmileIdDecision decision) {
        driverProfileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.setGovernmentIdDecidedDuringCheck(false);
            driverProfileRepository.save(profile);
            String said = switch (decision.kind()) {
                case APPROVE -> "approved it";
                case OPS -> "sent it for review";
                case RETRY -> "asked the driver to try again";
            };
            verificationAggregationService.recordLateProviderResult(userId, profile.getGovernmentIdStatus(),
                    decision.retryReason() != null ? decision.retryReason().name() : decision.outcome() == null ? null : decision.outcome().name(),
                    "Smile ID's result came in after ops decided - ops' decision stands. Smile ID " + said + ": " + decision.summary(),
                    DECIDED_BY);
        });
    }

    /** Clear means a genuine licence with their face on it - it must also be in their name, with their number, not expired. */
    @Override
    public SmileIdDecision onClear(String userId, SmileIdResult.IdFields idFields) {
        DriverProfile profile = driverProfileRepository.findByUserId(userId).orElseThrow();
        String recordFirst = idFields == null ? null : blankToNull(idFields.firstName());
        String recordLast = idFields == null ? null : blankToNull(idFields.lastName());
        String fullName = idFields == null ? null : blankToNull(idFields.fullName());
        String onLicence = (fullName != null ? fullName : safe(recordFirst) + " " + safe(recordLast)).trim();
        if (recordFirst == null || recordLast == null) {
            return SmileIdDecision.ops(VerificationCheckOutcome.PROVIDER_ERROR, null, true,
                    "Smile ID cleared this licence but the name on it couldn't be read (" + onLicence + ")");
        }
        // Sandbox checks are all sent as the test identity, whose sample licence carries its own name and number.
        IdentityVerificationService.Name expected = properties.isSandbox()
                ? SmileIdResults.testIdentityName(properties)
                : new IdentityVerificationService.Name(profile.getFirstName(), profile.getLastName());
        double score = NameMatcher.bestOrder(expected.firstName(), expected.lastName(), recordFirst, recordLast);
        String partial = null;
        if (score < properties.getNameMatchThreshold()) {
            String mismatch = "Licence is in the name of " + onLicence + ", profile says " + expected.firstName() + " " + expected.lastName();
            boolean sharesAName = NameMatcher.sharesAName(List.of(expected.firstName(), expected.lastName()),
                    IdentityVerificationService.recordNames(idFields), properties.getNameMatchThreshold());
            if (!properties.approvesPartialNames() || !sharesAName) {
                return SmileIdDecision.ops(VerificationCheckOutcome.NAME_MISMATCH, score, true, mismatch);
            }
            partial = "Approved on a partial name match - review: " + mismatch + ". ";
        }
        String number = normalize(idFields.idNumber());
        if (!properties.isSandbox() && number != null && !number.equals(normalize(profile.getIdNumber()))) {
            return SmileIdDecision.ops(VerificationCheckOutcome.DETAILS_MISMATCH, score, true,
                    "Licence number on the card is " + summaryMask(number) + ", driver entered " + summaryMask(profile.getIdNumber()));
        }
        LocalDate expires = parseDate(idFields.expirationDate());
        if (expires != null && expires.isBefore(LocalDate.now(LAGOS))) {
            return SmileIdDecision.ops(VerificationCheckOutcome.DETAILS_MISMATCH, score, true, "Licence expired on " + expires);
        }
        String summary = "Genuine licence in the name of " + onLicence
                + (number == null ? "" : ", number " + summaryMask(number)) + (expires == null ? "" : ", valid to " + expires)
                + ", live selfie matched the licence photo";
        return partial == null ? SmileIdDecision.approve(score, summary) : SmileIdDecision.approveForReview(score, partial + summary);
    }

    @Override
    public SmileIdDecision onReason(String status, String reason, String message) {
        if ("block".equals(status) && "unsupported_document".equals(reason)) {
            return SmileIdDecision.retry(SmileIdRetryReason.NOT_A_LICENCE, VerificationCheckOutcome.NOT_FOUND, true,
                    "Smile ID doesn't accept this document as a Nigerian driver's licence");
        }
        if ("document_expired".equals(reason)) {
            return SmileIdDecision.ops(VerificationCheckOutcome.DETAILS_MISMATCH, null, true, "Licence has expired: " + safe(message));
        }
        return null;
    }

    @Override
    public void approve(String userId, SmileIdResult.IdFields idFields) {
        verificationAggregationService.autoApproveGovernmentId(userId, DECIDED_BY);
    }

    /** Into the driver verification queue, as if they'd uploaded the licence for review - the check's note says what Smile ID found. */
    @Override
    public void sendToOps(String userId) {
        driverProfileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.setGovernmentIdStatus(VerificationStatus.IN_REVIEW);
            profile.setGovernmentIdSubmittedAt(Instant.now());
            profile.setGovernmentIdRejectionReason(null);
            driverProfileRepository.save(profile);
        });
    }

    private static String normalize(String number) {
        if (number == null) {
            return null;
        }
        String key = number.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Summaries are shown in the ops console, so a licence number in one keeps only its last four characters ("••••5678") -
     * the full number is an audited reveal (docs/ops-console/privacy.md). */
    static String summaryMask(String number) {
        if (number == null || number.isBlank()) {
            return number;
        }
        String n = number.trim();
        return "\u2022\u2022\u2022\u2022" + n.substring(Math.max(0, n.length() - 4));
    }
}
