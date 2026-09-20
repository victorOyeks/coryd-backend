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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    /**
     * Requested when a driver saves a licence number; handled after that save commits. Going
     * through an event rather than a direct call is what keeps the check off the request thread
     * without it racing the very write that triggered it - a direct @Async call from inside the
     * caller's transaction can read the profile before the caller has committed it.
     */
    public record LicenceCheckRequested(String driverId, String licenceNumber, String firstName, String lastName) {
    }

    public record PlateCheckRequested(String driverId, String plateNumber, String make, String color) {
    }

    @Transactional(readOnly = true)
    public List<VerificationCheck> checksFor(String driverId) {
        return verificationCheckRepository.findByDriverId(driverId);
    }

    /**
     * REQUIRES_NEW is mandatory, and Spring refuses to start without it: an AFTER_COMMIT listener
     * still has the completed transaction bound to it, so plain REQUIRED would join a transaction
     * that can never commit again. fallbackExecution covers callers outside a transaction
     * altogether, such as the dev seeder.
     */
    @Async("verificationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLicenceCheckRequested(LicenceCheckRequested event) {
        checkDriversLicence(event.driverId(), event.licenceNumber(), event.firstName(), event.lastName());
    }

    @Async("verificationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlateCheckRequested(PlateCheckRequested event) {
        checkPlateNumber(event.driverId(), event.plateNumber(), event.make(), event.color());
    }

    /**
     * Confirms the licence is real and belongs to the name on the profile. A match approves the
     * government-ID item outright; anything else is left for ops with a note.
     */
    public void checkDriversLicence(String driverId, String licenceNumber, String firstName, String lastName) {
        String subject = normalizeKey(licenceNumber);
        if (!properties.isEnabled() || subject == null || isBlank(firstName) || isBlank(lastName)) {
            return;
        }
        if (alreadySettled(driverId, VerificationCheckType.DRIVERS_LICENCE, subject)) {
            return;
        }

        PremblyClient.DriversLicenceResult result =
                premblyClient.verifyDriversLicence(licenceNumber.trim(), firstName.trim(), lastName.trim());

        if (result == null) {
            record(driverId, VerificationCheckType.DRIVERS_LICENCE, subject, VerificationCheckOutcome.PROVIDER_ERROR,
                    null, "Prembly did not answer - licence not checked");
            return;
        }
        PremblyClient.DriversLicenceResult.FrscData frsc = result.frscData();
        if (!result.status() || frsc == null) {
            record(driverId, VerificationCheckType.DRIVERS_LICENCE, subject, VerificationCheckOutcome.NOT_FOUND,
                    null, "FRSC has no record for this licence number: " + safe(result.detail()));
            return;
        }

        double score = NameMatcher.score(firstName, lastName, frsc.firstname(), frsc.lastname());
        String onRecord = (safe(frsc.firstname()) + " " + safe(frsc.lastname())).trim();
        String entered = firstName.trim() + " " + lastName.trim();

        if (score < properties.getNameMatchThreshold()) {
            record(driverId, VerificationCheckType.DRIVERS_LICENCE, subject, VerificationCheckOutcome.NAME_MISMATCH,
                    score, "Licence is held by " + onRecord + ", driver entered " + entered);
            return;
        }

        record(driverId, VerificationCheckType.DRIVERS_LICENCE, subject, VerificationCheckOutcome.MATCH,
                score, "FRSC confirmed " + onRecord);
        approveGovernmentId(driverId);
    }

    /**
     * Confirms the plate exists and that the car the driver described is the car on the register.
     * A match is recorded but does not approve the vehicle: the plate record names a car, not an
     * owner, so it cannot show this driver is entitled to drive it. Ops still decides.
     */
    public void checkPlateNumber(String driverId, String plateNumber, String declaredMake, String declaredColor) {
        String subject = normalizeKey(plateNumber);
        if (!properties.isEnabled() || subject == null) {
            return;
        }
        if (alreadySettled(driverId, VerificationCheckType.VEHICLE_PLATE, subject)) {
            return;
        }

        PremblyClient.VehicleResult result = premblyClient.verifyPlateNumber(plateNumber.trim());

        if (result == null) {
            record(driverId, VerificationCheckType.VEHICLE_PLATE, subject, VerificationCheckOutcome.PROVIDER_ERROR,
                    null, "Prembly did not answer - plate not checked");
            return;
        }
        PremblyClient.VehicleResult.Data data = result.data();
        if (!result.status() || data == null) {
            record(driverId, VerificationCheckType.VEHICLE_PLATE, subject, VerificationCheckOutcome.NOT_FOUND,
                    null, "No vehicle registered against this plate: " + safe(result.detail()));
            return;
        }

        String onRecord = (safe(data.vehicleName()) + " " + safe(data.vehicleColor())).trim();
        String declared = (safe(declaredMake) + " " + safe(declaredColor)).trim();

        if (properties.isCrossCheckVehicleDetails()
                && contradicts(declaredMake, data.vehicleName(), declaredColor, data.vehicleColor())) {
            record(driverId, VerificationCheckType.VEHICLE_PLATE, subject, VerificationCheckOutcome.DETAILS_MISMATCH,
                    null, "Plate is registered to a " + onRecord + ", driver declared a " + declared);
            return;
        }

        record(driverId, VerificationCheckType.VEHICLE_PLATE, subject, VerificationCheckOutcome.MATCH,
                null, "Register confirmed " + onRecord);
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

    private void record(String driverId, VerificationCheckType type, String subjectKey,
                        VerificationCheckOutcome outcome, Double score, String summary) {
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
        verificationCheckRepository.save(check);
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
