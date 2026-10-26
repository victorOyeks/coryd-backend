package org.coryd.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * Once a NIN check is approved, the name they typed (already matched against NIMC, or accepted by ops) is the
 * verified one and can't be changed in the app. The NIMC name and date of birth are kept on their identity check
 * for ops to refer to - nothing else from the record: the address, NIMC photo, phone and the rest stay with
 * Smile ID. Never in the sandbox, where every result carries the test identity's name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifiedIdentityService {

    private final IdentityVerificationRepository identityVerificationRepository;
    private final VerificationCheckRepository verificationCheckRepository;
    private final SmileIdProperties properties;
    private final ObjectMapper objectMapper;

    /** Smile ID approved the NIN check - idFields is the NIMC record it sent. */
    @Transactional
    public void applyFromNimc(String userId, SmileIdResult.IdFields idFields) {
        if (properties.isSandbox() || idFields == null) {
            return;
        }
        String first = properCase(idFields.firstName());
        String last = properCase(idFields.lastName());
        if (first == null || last == null) {
            return;
        }
        IdentityVerification verification = identityVerificationRepository.findByUserId(userId).orElse(null);
        if (verification == null) {
            return;
        }
        verification.setVerifiedFirstName(first);
        verification.setVerifiedMiddleName(properCase(idFields.otherNames()));
        verification.setVerifiedLastName(last);
        verification.setVerifiedDateOfBirth(date(idFields.dateOfBirth()));
        identityVerificationRepository.save(verification);
    }

    /** Ops approved the NIN check by hand - the NIMC record is the one on their latest Smile ID result, if any. */
    @Transactional
    public void applyFromLatestCheck(String userId) {
        verificationCheckRepository.findByDriverId(userId).stream()
                .filter(c -> (c.getCheckType() == VerificationCheckType.NIN_FACE || c.getCheckType() == VerificationCheckType.NIN_LOOKUP)
                        && c.getRawResponse() != null)
                .max(Comparator.comparing(VerificationCheck::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .flatMap(c -> read(c.getRawResponse()))
                .ifPresent(fields -> applyFromNimc(userId, fields));
    }

    /** Their NIN check is approved, so the profile name is the verified one: the app shows it read-only, and a
     * different name sent on a profile save is ignored. */
    public boolean nameVerified(String userId) {
        return identityVerificationRepository.findByUserId(userId)
                .map(v -> v.getStatus() == VerificationStatus.APPROVED).orElse(false);
    }

    private Optional<SmileIdResult.IdFields> read(String rawResponse) {
        try {
            return Optional.ofNullable(objectMapper.readValue(rawResponse, SmileIdResult.class).idFields());
        } catch (Exception e) {
            log.warn("Couldn't read the stored Smile ID result: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** NIMC sends names in capitals - "OYEKANMI" becomes "Oyekanmi", "ADE-JOHNSON" "Ade-Johnson", "O'BRIEN" "O'Brien". */
    static String properCase(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String lower = name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean startOfWord = true;
        for (char c : lower.toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = c == ' ' || c == '-' || c == '\'';
        }
        return out.toString();
    }

    /** NIMC's date of birth is yyyy-MM-dd; anything else is left out rather than guessed. */
    static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
