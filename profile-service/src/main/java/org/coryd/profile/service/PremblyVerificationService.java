package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.client.PremblyClient;
import org.coryd.profile.config.PremblyProperties;
import org.coryd.profile.dto.VerificationCheckOutcome;
import org.coryd.profile.dto.VerificationCheckType;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Turns a Prembly lookup into a decision. Two rules hold everywhere in here, and both exist
 * because an automated identity check is evidence, not a verdict:
 *
 * <ul>
 *   <li>A clean match may approve an item on its own - that is the point, it takes the routine
 *       cases off the ops queue.</li>
 *   <li>Nothing else may reject one. A near-miss name, a plate the register has never heard of, a
 *       provider that timed out - all leave the item exactly as it was, with the reason recorded
 *       for whoever reviews it. A driver must never be turned away because FRSC was down.</li>
 * </ul>
 *
 * <p>Checks run off the request thread: a driver saving their profile should not wait on FRSC, and
 * should not see an error if FRSC is unreachable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PremblyVerificationService {

    private final PremblyProperties properties;
    private final PremblyClient premblyClient;
    private final VerificationCheckRepository verificationCheckRepository;
    private final DriverProfileRepository driverProfileRepository;

    @Transactional(readOnly = true)
    public List<VerificationCheck> checksFor(String driverId) {
        return verificationCheckRepository.findByDriverId(driverId);
    }

    /**
     * Confirms the licence is real and belongs to the name on the profile. A match approves the
     * government-ID item outright; anything else is left for ops with a note.
     *
     * <p>Everything it needs is passed in, so it never re-reads what the caller just wrote. Call it
     * from another bean - a call from inside this class would bypass the proxy and run FRSC on the
     * request thread.
     */
    @Async("verificationExecutor")
    @Transactional
    public void checkDriversLicence(String driverId, String licenceNumber, String firstName, String lastName) {
        String subject = normalizeKey(licenceNumber);
        if (!properties.isEnabled() || subject == null || isBlank(firstName) || isBlank(lastName)) {
            log.debug("Skipping licence check for driver {}: enabled={}, licence={}, name present={}",
                    driverId, properties.isEnabled(), subject != null, !isBlank(firstName) && !isBlank(lastName));
            return;
        }
        if (alreadySettled(driverId, VerificationCheckType.DRIVERS_LICENCE, subject)) {
            log.debug("Licence {} already checked for driver {} - not spending another lookup", subject, driverId);
            return;
        }

        PremblyClient.DriversLicenceResult result =
                premblyClient.verifyDriversLicence(licenceNumber.trim(), firstName.trim(), lastName.trim());

        if (result == null) {
            record(driverId, VerificationCheckType.DRIVERS_LICENCE, subject, VerificationCheckOutcome.PROVIDER_ERROR,
                    null, "Prembly did not answer - licence not checked", null);
            return;
        }
        if (!result.status()) {
            record(driverId, VerificationCheckType.DRIVERS_LICENCE, subject, VerificationCheckOutcome.NOT_FOUND,
                    null, "FRSC has no record for this licence number: " + safe(result.detail()), result.reference());
            return;
        }
        // Prembly said yes but we could not find the names. That is our parsing, not their record,
        // and calling it NOT_FOUND would tell ops the opposite of the truth about a real licence.
        PremblyClient.DriversLicenceResult.FrscData frsc = result.frscData();
        if (frsc == null) {
            record(driverId, VerificationCheckType.DRIVERS_LICENCE, subject, VerificationCheckOutcome.PROVIDER_ERROR,
                    null, "Prembly verified this licence but the response could not be read", result.reference());
            return;
        }

        double score = NameMatcher.score(firstName, lastName, frsc.firstname(), frsc.lastname());
        String onRecord = (safe(frsc.firstname()) + " " + safe(frsc.lastname())).trim();
        String entered = firstName.trim() + " " + lastName.trim();

        if (score < properties.getNameMatchThreshold()) {
            record(driverId, VerificationCheckType.DRIVERS_LICENCE, subject, VerificationCheckOutcome.NAME_MISMATCH,
                    score, tag(result.sandbox()) + "Licence is held by " + onRecord + ", driver entered " + entered,
                    result.reference());
            return;
        }

        record(driverId, VerificationCheckType.DRIVERS_LICENCE, subject, VerificationCheckOutcome.MATCH,
                score, tag(result.sandbox()) + "FRSC confirmed " + onRecord, result.reference());
        approveGovernmentId(driverId);
    }

    /**
     * Confirms the plate exists and that the car the driver described is the car on the register.
     * A match is recorded but does not approve the vehicle: the plate record names a car, not an
     * owner, so it cannot show this driver is entitled to drive it. Ops still decides.
     */
    @Async("verificationExecutor")
    @Transactional
    public void checkPlateNumber(String driverId, String plateNumber, String declaredMake, String declaredColor) {
        String subject = normalizeKey(plateNumber);
        if (!properties.isEnabled() || subject == null) {
            log.debug("Skipping plate check for driver {}: enabled={}, plate={}",
                    driverId, properties.isEnabled(), subject != null);
            return;
        }
        if (alreadySettled(driverId, VerificationCheckType.VEHICLE_PLATE, subject)) {
            log.debug("Plate {} already checked for driver {} - not spending another lookup", subject, driverId);
            return;
        }

        PremblyClient.VehicleResult result = premblyClient.verifyPlateNumber(plateNumber.trim());

        if (result == null) {
            record(driverId, VerificationCheckType.VEHICLE_PLATE, subject, VerificationCheckOutcome.PROVIDER_ERROR,
                    null, "Prembly did not answer - plate not checked", null);
            return;
        }
        if (!result.status()) {
            record(driverId, VerificationCheckType.VEHICLE_PLATE, subject, VerificationCheckOutcome.NOT_FOUND,
                    null, "No vehicle registered against this plate: " + safe(result.detail()), result.reference());
            return;
        }
        PremblyClient.VehicleResult.Data data = result.data();
        if (data == null) {
            record(driverId, VerificationCheckType.VEHICLE_PLATE, subject, VerificationCheckOutcome.PROVIDER_ERROR,
                    null, "Prembly verified this plate but the response could not be read", result.reference());
            return;
        }

        String onRecord = (safe(data.vehicleName()) + " " + safe(data.vehicleColor())).trim();
        String declared = (safe(declaredMake) + " " + safe(declaredColor)).trim();

        if (properties.isCrossCheckVehicleDetails()
                && contradicts(declaredMake, data.vehicleName(), declaredColor, data.vehicleColor())) {
            record(driverId, VerificationCheckType.VEHICLE_PLATE, subject, VerificationCheckOutcome.DETAILS_MISMATCH,
                    null, tag(result.sandbox()) + "Plate is registered to a " + onRecord + ", driver declared a "
                            + declared, result.reference());
            return;
        }

        record(driverId, VerificationCheckType.VEHICLE_PLATE, subject, VerificationCheckOutcome.MATCH,
                null, tag(result.sandbox()) + "Register confirmed " + onRecord, result.reference());
    }

    private void approveGovernmentId(String driverId) {
        driverProfileRepository.findByUserId(driverId).ifPresent(profile -> {
            if (profile.getGovernmentIdStatus() == VerificationStatus.APPROVED) {
                return;
            }
            profile.setGovernmentIdStatus(VerificationStatus.APPROVED);
            profile.setGovernmentIdRejectionReason(null);
            driverProfileRepository.save(profile);
            log.info("Auto-approved government ID for driver {} on an FRSC licence match", driverId);
        });
    }

    /**
     * A settled check is one we already paid for that already told us something. Only a provider
     * error is worth spending another call on - a mismatch would say the same thing again until
     * the driver changes what they entered, which changes the key and re-checks anyway.
     */
    private boolean alreadySettled(String driverId, VerificationCheckType type, String subjectKey) {
        return verificationCheckRepository.findByDriverIdAndCheckTypeAndSubjectKey(driverId, type, subjectKey)
                .filter(check -> check.getOutcome() != VerificationCheckOutcome.PROVIDER_ERROR)
                .isPresent();
    }

    /** A sandbox key answers with fixture data for any input, so an approval from one must say so. */
    private static String tag(boolean sandbox) {
        return sandbox ? "[SANDBOX] " : "";
    }

    private void record(String driverId, VerificationCheckType type, String subjectKey,
                        VerificationCheckOutcome outcome, Double score, String summary, String reference) {
        VerificationCheck check = verificationCheckRepository
                .findByDriverIdAndCheckTypeAndSubjectKey(driverId, type, subjectKey)
                .orElseGet(() -> VerificationCheck.builder()
                        .driverId(driverId)
                        .checkType(type)
                        .subjectKey(subjectKey)
                        .build());
        check.setOutcome(outcome);
        check.setNameMatchScore(score);
        check.setProviderSummary(truncate(summary));
        check.setProviderReference(reference);
        verificationCheckRepository.save(check);
        log.info("Prembly {} check for driver {} on {}: {} - {}", type, driverId, subjectKey, outcome, summary);
    }

    /**
     * Only a positive contradiction counts. A blank on either side means the register did not say,
     * and "did not say" is not "disagreed" - treating it as a mismatch would push clean vehicles
     * into the ops queue for no reason.
     */
    private static boolean contradicts(String declaredMake, String recordMake,
                                       String declaredColor, String recordColor) {
        return disagrees(declaredMake, recordMake) || disagrees(declaredColor, recordColor);
    }

    /** Substring either way, because the register says "Toyota" where a driver says "Toyota Corolla". */
    private static boolean disagrees(String declared, String onRecord) {
        if (isBlank(declared) || isBlank(onRecord)) {
            return false;
        }
        String a = declared.trim().toLowerCase(Locale.ROOT);
        String b = onRecord.trim().toLowerCase(Locale.ROOT);
        return !a.contains(b) && !b.contains(a);
    }

    /** Plates and licence numbers get written with and without spaces and hyphens; the key is neither. */
    private static String normalizeKey(String value) {
        if (isBlank(value)) {
            return null;
        }
        String key = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }
}
