package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.VerificationItemDecidedEvent;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.OpsAccountProfile;
import org.coryd.profile.dto.OpsDetailsEditRequest;
import org.coryd.profile.dto.OpsEditResult;
import org.coryd.profile.dto.OpsVehicleEditRequest;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.entity.VerificationDecisionLog;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The ops console's account screens, profile-service's half: one person's profile data in one call, and ops editing a
 * person's name and email or a driver's vehicle. Every edit returns what changed (before and after) - ops-service records
 * it in the account history with the admin's reason. Phone numbers aren't here: they belong to identity-service and ops
 * can't change them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAccountService {

    private final DriverProfileRepository driverProfileRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final VehicleRepository vehicleRepository;
    private final EmergencyContactRepository emergencyContactRepository;
    private final IdentityVerificationRepository identityVerificationRepository;
    private final VerificationDecisionLogRepository decisionLogRepository;
    private final VerificationAggregationService verificationAggregationService;
    private final LicenceAttemptsService licenceAttemptsService;
    private final IdentityServiceClient identityServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional(readOnly = true)
    public OpsAccountProfile profile(String userId) {
        DriverProfile driver = driverProfileRepository.findByUserId(userId).orElse(null);
        RiderProfile rider = riderProfileRepository.findByUserId(userId).orElse(null);
        if (driver == null && rider == null) {
            throw new ResourceNotFoundException("No rider or driver profile for this user");
        }
        Vehicle primary = driver == null ? null : verificationAggregationService.primaryVehicleOf(userId);
        List<OpsAccountProfile.VehicleView> vehicles = vehicleRepository.findByDriverId(userId).stream()
                .sorted(Comparator.comparing(Vehicle::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(v -> vehicleView(v, primary))
                .toList();
        List<OpsAccountProfile.Contact> contacts = emergencyContactRepository.findByRiderIdOrderByCreatedAtAsc(userId).stream()
                .map(c -> new OpsAccountProfile.Contact(c.getId(), c.getName(), c.getPhone(), c.isPrimary()))
                .toList();
        OpsAccountProfile.Identity identity = identityVerificationRepository.findByUserId(userId)
                .map(v -> new OpsAccountProfile.Identity(v.getStatus(), v.getSubmittedAt(), v.getDecidedAt(), v.getRejectionReason()))
                .orElse(null);
        return new OpsAccountProfile(userId, driver == null ? null : driverPart(driver),
                rider == null ? null : new OpsAccountProfile.Rider(rider.getFirstName(), rider.getLastName(), rider.getEmail(), rider.getPhotoUrl()),
                vehicles, contacts, identity);
    }

    private OpsAccountProfile.Driver driverPart(DriverProfile driver) {
        String id = driver.getUserId();
        List<OpsAccountProfile.Item> items = verificationAggregationService.currentItems(id).stream()
                .map(e -> new OpsAccountProfile.Item(e.item(), e.item().label(), e.status(), e.awaitingProvider(), e.submittedAt(), e.resubmitted()))
                .toList();
        return new OpsAccountProfile.Driver(driver.getFirstName(), driver.getLastName(), driver.getEmail(), driver.getPhotoUrl(),
                org.coryd.common.util.PiiMask.birthYear(driver.getDateOfBirth()), org.coryd.common.util.PiiMask.trailing(driver.getIdNumber()),
                verificationAggregationService.overallStatus(id), driver.getVerifiedSince(),
                items, licenceAttemptsService.attempts(id));
    }

    /**
     * Name and email, on the person's driver and rider profiles alike (they're one person), and the name on their account
     * (identity-service, which puts it in their next token). A name a NIN check verified can still be corrected by ops -
     * the edit and its reason are in the account history.
     */
    @Transactional
    public OpsEditResult editDetails(String userId, OpsDetailsEditRequest request) {
        DriverProfile driver = driverProfileRepository.findByUserId(userId).orElse(null);
        RiderProfile rider = riderProfileRepository.findByUserId(userId).orElse(null);
        if (driver == null && rider == null) {
            throw new ResourceNotFoundException("No rider or driver profile for this user");
        }
        String firstName = cleaned(request.firstName(), "first name");
        String lastName = cleaned(request.lastName(), "last name");
        String email = cleaned(request.email(), "email");
        if (firstName == null && lastName == null && email == null) {
            throw new BadRequestException("Send the first name, last name or email to change");
        }
        String currentFirst = driver != null ? driver.getFirstName() : rider.getFirstName();
        String currentLast = driver != null ? driver.getLastName() : rider.getLastName();
        String currentEmail = driver != null && driver.getEmail() != null ? driver.getEmail() : rider == null ? null : rider.getEmail();

        List<OpsEditResult.Change> changes = new ArrayList<>();
        change(changes, "firstName", currentFirst, firstName);
        change(changes, "lastName", currentLast, lastName);
        change(changes, "email", currentEmail, email == null ? null : email.toLowerCase(Locale.ROOT));
        if (changes.isEmpty()) {
            throw new BadRequestException("Nothing to change - these are already the details on the account");
        }
        String newEmail = email == null ? null : email.toLowerCase(Locale.ROOT);
        if (driver != null) {
            driver.setFirstName(firstName != null ? firstName : driver.getFirstName());
            driver.setLastName(lastName != null ? lastName : driver.getLastName());
            driver.setEmail(newEmail != null ? newEmail : driver.getEmail());
            driverProfileRepository.save(driver);
        }
        if (rider != null) {
            rider.setFirstName(firstName != null ? firstName : rider.getFirstName());
            rider.setLastName(lastName != null ? lastName : rider.getLastName());
            rider.setEmail(newEmail != null ? newEmail : rider.getEmail());
            riderProfileRepository.save(rider);
        }
        if (firstName != null || lastName != null) {
            identityServiceClient.syncName(userId, driver != null ? driver.getFirstName() : rider.getFirstName(),
                    driver != null ? driver.getLastName() : rider.getLastName());
        }
        log.info("Details of {} changed by ops: {}", userId, changes.stream().map(OpsEditResult.Change::field).toList());
        return new OpsEditResult(changes, null, false);
    }

    /**
     * A driver's vehicle. Changing what identifies the car - plate, make, model or year - on an approved vehicle sends its
     * registration back to review: the approved documents were for the car as it was. The driver can't post trips until
     * ops approves it again (when it's their primary vehicle), and is told it's being checked. Colour and seat count
     * don't identify the car, so they change with no review.
     */
    @Transactional
    public OpsEditResult editVehicle(String driverId, String vehicleId, OpsVehicleEditRequest request, String opsName, String opsId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .filter(v -> driverId.equals(v.getDriverId()))
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found for this driver"));
        String make = cleaned(request.make(), "make");
        String model = cleaned(request.model(), "model");
        String color = cleaned(request.color(), "colour");
        String plate = cleaned(request.plateNumber(), "plate number");
        plate = plate == null ? null : plate.toUpperCase(Locale.ROOT);
        if (make == null && model == null && color == null && plate == null && request.year() == null && request.seatCapacity() == null) {
            throw new BadRequestException("Send the vehicle details to change");
        }
        List<OpsEditResult.Change> changes = new ArrayList<>();
        change(changes, "make", vehicle.getMake(), make);
        change(changes, "model", vehicle.getModel(), model);
        change(changes, "year", text(vehicle.getYear()), text(request.year()));
        change(changes, "color", vehicle.getColor(), color);
        change(changes, "plateNumber", vehicle.getPlateNumber(), plate);
        change(changes, "seatCapacity", text(vehicle.getSeatCapacity()), text(request.seatCapacity()));
        if (changes.isEmpty()) {
            throw new BadRequestException("Nothing to change - these are already the vehicle's details");
        }
        if (plate != null && !plate.equalsIgnoreCase(vehicle.getPlateNumber()) && vehicleRepository.existsByPlateNumberIgnoreCase(plate)) {
            throw new BadRequestException("A vehicle with this plate number is already registered");
        }
        boolean identityChanged = changes.stream().anyMatch(c -> List.of("make", "model", "year", "plateNumber").contains(c.field()));
        boolean sendBack = identityChanged && vehicle.getVerificationStatus() == VerificationStatus.APPROVED;
        VerificationStatus overallBefore = verificationAggregationService.overallStatus(driverId);

        if (make != null) vehicle.setMake(make);
        if (model != null) vehicle.setModel(model);
        if (request.year() != null) vehicle.setYear(request.year());
        if (color != null) vehicle.setColor(color);
        if (plate != null) vehicle.setPlateNumber(plate);
        if (request.seatCapacity() != null) vehicle.setSeatCapacity(request.seatCapacity());
        if (sendBack) {
            vehicle.setVerificationStatus(VerificationStatus.IN_REVIEW);
            vehicle.setSubmittedAt(Instant.now());
            vehicle.setResubmitted(false);
            vehicle.setRejectionReason(null);
        }
        vehicleRepository.save(vehicle);

        if (sendBack) {
            String by = opsName == null || opsName.isBlank() ? "ops" : opsName;
            String what = changes.stream().filter(c -> !"color".equals(c.field()) && !"seatCapacity".equals(c.field()))
                    .map(c -> c.field() + " " + c.before() + " -> " + c.after()).reduce((a, b) -> a + ", " + b).orElse("");
            decisionLogRepository.save(VerificationDecisionLog.builder()
                    .driverId(driverId)
                    .item(DecisionSubject.VEHICLE)
                    .action(VerificationDecisionAction.SENT_BACK_TO_REVIEW)
                    .previousStatus(VerificationStatus.APPROVED)
                    .status(VerificationStatus.IN_REVIEW)
                    .note(truncate("Vehicle details changed by ops (" + what + ") - registration back in review"))
                    .decidedBy(by)
                    .decidedById(opsId)
                    .build());
            if (overallBefore == VerificationStatus.APPROVED && verificationAggregationService.overallStatus(driverId) != VerificationStatus.APPROVED) {
                verificationAggregationService.publishPostingBlocked(driverId, "Vehicle details changed - back under review", by);
            }
            kafkaTemplate.send(KafkaTopics.VERIFICATION_ITEM_DECIDED, VerificationItemDecidedEvent.builder()
                    .driverId(driverId)
                    .email(driverProfileRepository.findByUserId(driverId).map(DriverProfile::getEmail).orElse(null))
                    .item(ReviewableItem.VEHICLE.name())
                    .itemLabel(ReviewableItem.VEHICLE.label())
                    .approved(false)
                    .reviewedBy(by)
                    .decidedAt(LocalDateTime.now())
                    .action(VerificationDecisionAction.SENT_BACK_TO_REVIEW.name())
                    .build());
        }
        log.info("Vehicle {} of {} changed by ops{}: {}", vehicleId, driverId, sendBack ? " - back in review" : "",
                changes.stream().map(OpsEditResult.Change::field).toList());
        return new OpsEditResult(changes, vehicleView(vehicle, verificationAggregationService.primaryVehicleOf(driverId)), sendBack);
    }

    private static OpsAccountProfile.VehicleView vehicleView(Vehicle v, Vehicle primary) {
        return new OpsAccountProfile.VehicleView(v.getId(), v.getMake(), v.getModel(), v.getYear(), v.getColor(), v.getPlateNumber(),
                v.getSeatCapacity(), v.isHasAC(), v.getVerificationStatus(), v.getRejectionReason(),
                primary != null && primary.getId().equals(v.getId()));
    }

    private static void change(List<OpsEditResult.Change> changes, String field, String before, String after) {
        if (after != null && !after.equals(before)) {
            changes.add(new OpsEditResult.Change(field, before, after));
        }
    }

    /** Null when not sent; a value sent blank is refused. */
    private static String cleaned(String value, String label) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new BadRequestException("The " + label + " can't be blank");
        }
        return trimmed;
    }

    private static String text(Integer value) {
        return value == null ? null : value.toString();
    }

    private static String truncate(String note) {
        return note.length() > 500 ? note.substring(0, 500) : note;
    }
}
