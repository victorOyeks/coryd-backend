package org.coryd.profile.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.devseed.SeedFixtures;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.profile.dto.ReviewableItem;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.service.VerificationAggregationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * dev/test only - the profile-service half of the fleet identity-service's DataSeeder starts:
 * for every SeedFixtures.DRIVERS entry, builds a submitted-and-complete DriverProfile/Vehicle/
 * pair of DriverDocuments, then runs the exact same VerificationAggregationService.decide() an
 * OPS_ADMIN would use to approve a real driver - so it ends up APPROVED via the real code path
 * (Kafka DriverApprovedEvent included, which is what flips trip-service's DriverVerificationCache
 * so these drivers can actually post routes), not a shortcut that bypasses it. Every
 * SeedFixtures.RIDERS entry gets a plain RiderProfile - riders don't have a verification step.
 * Idempotent: safe to run on every restart.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private static final LocalDate FAR_FUTURE_EXPIRY = LocalDate.of(2030, 1, 1);

    private final DriverProfileRepository driverProfileRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverDocumentRepository driverDocumentRepository;
    private final RiderProfileRepository riderProfileRepository;
    private final VerificationAggregationService verificationAggregationService;

    @Override
    public void run(ApplicationArguments args) {
        for (int i = 0; i < SeedFixtures.DRIVERS.size(); i++) {
            seedDriver(SeedFixtures.DRIVERS.get(i), i + 1);
        }
        SeedFixtures.RIDERS.forEach(this::seedRider);
        log.info("Seeded {} fully-approved drivers (ready to post routes) and {} rider profiles (ready to search)",
                SeedFixtures.DRIVERS.size(), SeedFixtures.RIDERS.size());
    }

    private void seedDriver(SeedFixtures.SeedPerson driver, int index) {
        String driverId = driver.id();

        DriverProfile profile = driverProfileRepository.findByUserId(driverId)
                .orElseGet(() -> DriverProfile.builder().userId(driverId).build());
        profile.setLegalName(driver.fullName());
        profile.setEmail("driver" + index + "@coryd.test");
        profile.setDateOfBirth(LocalDate.of(1995, 1, 1));
        profile.setIdNumber("SEED-ID-" + index);
        profile.setGovernmentIdUrl("https://example.com/coryd-seed/gov-id-" + index + ".jpg");
        profile.setSelfieUrl("https://example.com/coryd-seed/selfie-" + index + ".jpg");
        profile.setSelfieSubmittedAt(Instant.now());
        driverProfileRepository.save(profile);

        Vehicle vehicle = vehicleRepository.findByDriverId(driverId).stream().findFirst()
                .orElseGet(() -> Vehicle.builder().driverId(driverId).build());
        vehicle.setMake("Toyota");
        vehicle.setModel("Corolla");
        vehicle.setYear(2018);
        vehicle.setColor("Black");
        vehicle.setPlateNumber(String.format("SEED-%03d", index));
        vehicle.setSeatCapacity(4);
        vehicle.setExteriorPhotoUrl("https://example.com/coryd-seed/exterior-" + index + ".jpg");
        vehicle.setInteriorPhotoUrl("https://example.com/coryd-seed/interior-" + index + ".jpg");
        vehicle.setHasAC(true);
        vehicleRepository.save(vehicle);

        seedDocument(driverId, DocumentType.INSURANCE, index);
        seedDocument(driverId, DocumentType.ROADWORTHINESS_CERT, index);

        VerificationDecisionBatchRequest approveEverything = new VerificationDecisionBatchRequest(
                List.of(
                        new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.APPROVED, null),
                        new ItemDecision(ReviewableItem.SELFIE, VerificationStatus.APPROVED, null),
                        new ItemDecision(ReviewableItem.VEHICLE, VerificationStatus.APPROVED, null),
                        new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.APPROVED, null),
                        new ItemDecision(ReviewableItem.ROADWORTHINESS_CERT, VerificationStatus.APPROVED, null)
                ),
                "DataSeeder"
        );
        verificationAggregationService.decide(driverId, UserRole.OPS_ADMIN, approveEverything);
    }

    private void seedDocument(String driverId, DocumentType type, int index) {
        DriverDocument document = driverDocumentRepository.findByDriverIdAndDocumentType(driverId, type)
                .orElseGet(() -> DriverDocument.builder().driverId(driverId).documentType(type).build());
        document.setDocumentUrl("https://example.com/coryd-seed/" + type.name().toLowerCase() + "-" + index + ".jpg");
        document.setExpiresOn(FAR_FUTURE_EXPIRY);
        driverDocumentRepository.save(document);
    }

    private void seedRider(SeedFixtures.SeedPerson rider) {
        RiderProfile profile = riderProfileRepository.findByUserId(rider.id())
                .orElseGet(() -> RiderProfile.builder().userId(rider.id()).build());
        profile.setFullName(rider.fullName());
        profile.setEmail(rider.fullName().toLowerCase().replace(" ", "") + "@coryd.test");
        riderProfileRepository.save(profile);
    }
}
