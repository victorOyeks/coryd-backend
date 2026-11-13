package org.coryd.profile.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.PiiField;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.util.PiiMask;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * The audited reveal behind POST /ops/users/{userId}/reveal (docs/ops-console/privacy.md). Every ops response shows these
 * masked; this hands one full value to one admin who said why, and records PII_REVEALED (who, whose, which, why - never the
 * value) before returning it. ops-service checks the admin's role and rate limit first; this checks they are OPS_ADMIN.
 *
 * <ul>
 *   <li>LICENCE_NUMBER: the number the driver entered (the one checked against the licence).</li>
 *   <li>DATE_OF_BIRTH: the driver's profile date of birth; for a rider (or a driver without one), the date on the NIMC
 *       record their NIN check returned.</li>
 *   <li>NIN: CoRyd keeps only the last four digits and a fingerprint. The full number is shown only when the latest Smile ID
 *       NIN result on file carries it (and it ends in those four digits); otherwise 404, and nothing is recorded.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PiiRevealService {

    public static final int EXPIRES_IN_SECONDS = 60;
    public static final String NEED_A_REASON = "Say why you need to see it - it goes in the audit log";

    private final PiiRevealLogRepository revealLogRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final VerificationCheckRepository verificationCheckRepository;
    private final ObjectMapper objectMapper;

    /** source: where the value came from - PROFILE (what the driver entered) or NIMC_RECORD (Smile ID's NIN result). */
    public record Revealed(PiiField field, String value, String source, Instant revealedAt, int expiresInSeconds) {
    }

    @Transactional
    public Revealed reveal(String userId, PiiField field, String reason, CoRydUserDetails admin) {
        if (admin == null || admin.getActiveRole() != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can reveal personal details");
        }
        if (field == null) {
            throw new BadRequestException("field must be one of NIN, LICENCE_NUMBER or DATE_OF_BIRTH");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new BadRequestException(NEED_A_REASON);
        }
        if (why.length() > 500) {
            throw new BadRequestException("Keep the reason under 500 characters");
        }
        Value value = switch (field) {
            case LICENCE_NUMBER -> licenceNumber(userId);
            case DATE_OF_BIRTH -> dateOfBirth(userId);
            case NIN -> nin(userId);
        };
        Instant now = Instant.now();
        String name = admin.getFullName();
        revealLogRepository.save(PiiRevealLog.builder()
                .userId(userId)
                .field(field)
                .actorId(admin.getUserId())
                .actorName(name == null || name.isBlank() ? null : name.trim())
                .reason(why)
                .maskedValue(value.masked())
                .build());
        log.info("PII_REVEALED {} of {} to {}", field, userId, admin.getUserId());
        return new Revealed(field, value.value(), value.source(), now, EXPIRES_IN_SECONDS);
    }

    private record Value(String value, String masked, String source) {
    }

    private Value licenceNumber(String userId) {
        String number = driverProfileRepository.findByUserId(userId).map(DriverProfile::getIdNumber)
                .filter(n -> n != null && !n.isBlank())
                .orElseThrow(() -> new ResourceNotFoundException("This person has no driver's licence number on file"));
        return new Value(number, PiiMask.trailing(number), "PROFILE");
    }

    private Value dateOfBirth(String userId) {
        Optional<LocalDate> profile = driverProfileRepository.findByUserId(userId).map(DriverProfile::getDateOfBirth);
        if (profile.isPresent()) {
            return new Value(profile.get().toString(), String.valueOf(profile.get().getYear()), "PROFILE");
        }
        LocalDate nimc = identityVerificationRepository.findByUserId(userId).map(IdentityVerification::getVerifiedDateOfBirth)
                .orElseThrow(() -> new ResourceNotFoundException("This person has no date of birth on file"));
        return new Value(nimc.toString(), String.valueOf(nimc.getYear()), "NIMC_RECORD");
    }

    private Value nin(String userId) {
        String last4 = identityVerificationRepository.findByUserId(userId).map(IdentityVerification::getNinLast4)
                .filter(n -> n != null && !n.isBlank())
                .orElseThrow(() -> new ResourceNotFoundException("This person hasn't given a NIN"));
        String full = verificationCheckRepository.findByDriverId(userId).stream()
                .filter(c -> (c.getCheckType() == VerificationCheckType.NIN_FACE || c.getCheckType() == VerificationCheckType.NIN_LOOKUP)
                        && c.getRawResponse() != null)
                .sorted(Comparator.comparing(VerificationCheck::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .map(c -> idNumber(c.getRawResponse()))
                .filter(Objects::nonNull)
                .filter(n -> n.matches("\\d{11}") && n.endsWith(last4))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("CoRyd keeps only the last four digits of this NIN ("
                        + PiiMask.nin(last4) + ") - look the job up in the Smile ID portal if you need the full number"));
        return new Value(full, PiiMask.nin(last4), "NIMC_RECORD");
    }

    private String idNumber(String raw) {
        try {
            SmileIdResult result = objectMapper.readValue(raw, SmileIdResult.class);
            return result.idFields() == null || result.idFields().idNumber() == null ? null : result.idFields().idNumber().trim();
        } catch (Exception e) {
            return null;
        }
    }
}
