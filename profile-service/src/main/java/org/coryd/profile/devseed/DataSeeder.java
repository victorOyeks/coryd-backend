package org.coryd.profile.devseed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.devseed.SeedFixtures;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.client.IdentityAuthClient;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.UpdateRiderProfileRequest;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.service.DriverDocumentService;
import org.coryd.profile.service.DriverProfileService;
import org.coryd.profile.service.RiderProfileService;
import org.coryd.profile.service.VehicleService;
import org.coryd.profile.service.VerificationAggregationService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * dev/test only - the profile-service half of the fleet identity-service's DataSeeder starts:
 * for every SeedFixtures.DRIVERS entry, resolves its real userId by logging in as it (see
 * IdentityAuthClient - a fixed id doesn't survive identity-service's own UUID generation, so
 * this is how the two independently-seeded services end up agreeing on the same id), builds a
 * submitted-and-complete DriverProfile/Vehicle/pair of DriverDocuments against that userId (via
 * DriverProfileService.updateMine(), not the repository directly, so this goes through the exact
 * same code path a real driver's own profile edit does - including publishing
 * DriverProfileCreatedEvent, which is what gives every seeded driver a system starting rating
 * too), then runs the exact same VerificationAggregationService.decide() an OPS_ADMIN would use
 * to approve a real driver - so it ends up APPROVED via the real code path (Kafka
 * DriverApprovedEvent included, which is what flips trip-service's DriverVerificationCache so
 * these drivers can actually post routes), not a shortcut that bypasses it. Every
 * SeedFixtures.RIDERS entry gets a plain RiderProfile via RiderProfileService.updateMine() - same
 * reasoning, riders just don't have a verification step. Seeds each account once: a driver already
 * fully approved, or a rider who already has a profile, is skipped - re-deciding on every restart
 * pushed four "approved" notifications to each seed driver per deploy and wiped any test edits.
 */
@Slf4j
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private static final LocalDate FAR_FUTURE_EXPIRY = LocalDate.of(2030, 1, 1);

    /** Shaped like real FRSC numbers and real Nigerian plates, so seeded data passes the same
     * validation a driver's own entry does and nothing reads as obviously fake on screen. */
    private static final List<String> LICENCE_PREFIXES = List.of("LAG", "AAD", "FKJ", "ABC", "KJA");
    private static final List<String> PLATE_PREFIXES = List.of("KJA", "LND", "AGL", "EKY", "IKJ");
    private static final List<Car> CARS = List.of(
            new Car("Toyota", "Corolla", "Black", 2018, 4),
            new Car("Honda", "Accord", "Silver", 2016, 4),
            new Car("Toyota", "Camry", "Grey", 2019, 4),
            new Car("Kia", "Rio", "Blue", 2020, 4),
            new Car("Hyundai", "Elantra", "White", 2017, 4),
            new Car("Toyota", "Sienna", "Red", 2015, 6),
            new Car("Nissan", "Almera", "Black", 2018, 4),
            new Car("Honda", "Civic", "Blue", 2021, 4),
            new Car("Toyota", "Highlander", "Silver", 2014, 6),
            new Car("Mazda", "Mazda3", "Grey", 2019, 4));

    private record Car(String make, String model, String color, int year, int seats) {
    }

    private final DriverProfileService driverProfileService;
    private final VehicleService vehicleService;
    private final VehicleRepository vehicleRepository;
    private final DriverDocumentService driverDocumentService;
    private final RiderProfileService riderProfileService;
    private final VerificationAggregationService verificationAggregationService;
    private final IdentityAuthClient identityAuthClient;
    private final RiderProfileRepository riderProfileRepository;

    @Override
    public void run(ApplicationArguments args) {
        int drivers = 0;
        for (int i = 0; i < SeedFixtures.DRIVERS.size(); i++) {
            drivers += seedDriver(SeedFixtures.DRIVERS.get(i), i + 1) ? 1 : 0;
        }
        int riders = 0;
        for (SeedFixtures.SeedPerson rider : SeedFixtures.RIDERS) {
            riders += seedRider(rider) ? 1 : 0;
        }
        log.info("Seeded {} new fully-approved drivers and {} new rider profiles ({} drivers / {} riders already in place)",
                drivers, riders, SeedFixtures.DRIVERS.size() - drivers, SeedFixtures.RIDERS.size() - riders);
    }

    private boolean seedDriver(SeedFixtures.SeedPerson driver, int index) {
        String driverId = identityAuthClient.resolveUserId(driver.phone(), SeedFixtures.SEED_PASSWORD, UserRole.DRIVER);
        if (verificationAggregationService.getStatus(driverId).overallStatus() == VerificationStatus.APPROVED) {
            return false;
        }

        driverProfileService.updateMine(driverId, new UpdateDriverProfileRequest(
                driver.firstName(),
                driver.lastName(),
                null,
                "driver" + index + "@example.com",
                LocalDate.of(1980 + (index % 20), 1 + (index % 12), 1 + (index % 28)),
                "https://example.com/coryd-seed/gov-id-" + index + ".jpg",
                "coryd-seed/gov-id-" + index,
                licenceNumber(index),
                "Next of kin " + index,
                String.format("080%08d", index)
        ));

        Vehicle vehicle = seedVehicle(driverId, index);
        seedDocument(driverId, vehicle.getId(), DocumentType.INSURANCE, index);
        seedDocument(driverId, vehicle.getId(), DocumentType.ROADWORTHINESS_CERT, index);

        VerificationDecisionBatchRequest approveEverything = new VerificationDecisionBatchRequest(
                List.of(
                        new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.APPROVED, null),
                        new ItemDecision(ReviewableItem.VEHICLE, VerificationStatus.APPROVED, null),
                        new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.APPROVED, null),
                        new ItemDecision(ReviewableItem.ROADWORTHINESS_CERT, VerificationStatus.APPROVED, null)
                ),
                "DataSeeder"
        );
        verificationAggregationService.decide(driverId, UserRole.OPS_ADMIN, approveEverything);
        return true;
    }

    /** Through VehicleService, not the repository, for the same reason the profile goes through
     * DriverProfileService - the seeded rows then carry everything a real registration sets. */
    private Vehicle seedVehicle(String driverId, int index) {
        Car car = CARS.get((index - 1) % CARS.size());
        VehicleRegistrationRequest request = new VehicleRegistrationRequest(
                car.make(), car.model(), car.year(), car.color(), plateNumber(index), car.seats(),
                "https://example.com/coryd-seed/exterior-" + index + ".jpg",
                "coryd-seed/exterior-" + index,
                "https://example.com/coryd-seed/interior-" + index + ".jpg",
                "coryd-seed/interior-" + index,
                true, true);

        return vehicleRepository.findByDriverId(driverId).stream().findFirst()
                .map(existing -> vehicleService.update(existing.getId(), driverId, request))
                .orElseGet(() -> vehicleService.register(driverId, driverId, request));
    }

    private void seedDocument(String driverId, String vehicleId, DocumentType type, int index) {
        driverDocumentService.create(driverId, new CreateDriverDocumentRequest(
                type,
                "https://example.com/coryd-seed/" + type.name().toLowerCase() + "-" + index + ".jpg",
                "coryd-seed/" + type.name().toLowerCase() + "-" + index,
                FAR_FUTURE_EXPIRY,
                vehicleId));
    }

    /** 3 letters + 8 digits - inside the 8-20 alphanumeric rule the real entry path enforces. */
    public static String licenceNumber(int index) {
        return LICENCE_PREFIXES.get((index - 1) % LICENCE_PREFIXES.size()) + String.format("%08d", index);
    }

    /** Nigerian civilian format: three letters, three digits, two letters. */
    private static String plateNumber(int index) {
        return String.format("%s%03dXA", PLATE_PREFIXES.get((index - 1) % PLATE_PREFIXES.size()), index);
    }

    private boolean seedRider(SeedFixtures.SeedPerson rider) {
        String riderId = identityAuthClient.resolveUserId(rider.phone(), SeedFixtures.SEED_PASSWORD, UserRole.RIDER);
        if (riderProfileRepository.findByUserId(riderId).isPresent()) {
            return false;
        }
        riderProfileService.updateMine(riderId, new UpdateRiderProfileRequest(
                rider.firstName(),
                rider.lastName(),
                rider.fullName().toLowerCase().replace(" ", "") + "@example.com",
                null,
                "Next of kin for " + rider.firstName(),
                String.format("081%08d", rider.phone().hashCode() & 0xFFFF)
        ));
        return true;
    }
}
