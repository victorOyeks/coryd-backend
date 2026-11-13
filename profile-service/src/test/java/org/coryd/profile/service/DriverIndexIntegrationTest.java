package org.coryd.profile.service;

import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.DriverDirectorySort;
import org.coryd.common.enums.DriverDirectoryStatus;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.events.DriverEarningsChangedEvent;
import org.coryd.common.kafka.events.DriverStatsChangedEvent;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.dto.DriverIndexView;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.scheduler.DriverIndexJobs;
import org.coryd.common.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * driver_index: kept current by every driver save and by trip, payment and identity events, and queried in the database
 * for the Drivers Directory - filter, sort, search and page. Against a throwaway Postgres container.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({VerificationAggregationService.class, DriverProfileService.class, VehicleService.class, DriverDocumentService.class,
        EmergencyContactService.class, RiderProfileService.class, ReplacedFiles.class, DriverIndexService.class, DriverIndexer.class,
        DriverIndexJobs.class})
class DriverIndexIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired DriverIndexService index;
    @Autowired DriverIndexJobs jobs;
    @Autowired VerificationAggregationService verification;
    @Autowired DriverProfileService driverProfileService;
    @Autowired VehicleService vehicleService;
    @Autowired DriverDocumentService driverDocumentService;
    @Autowired DriverIndexRepository driverIndexRepository;
    @Autowired DriverProfileRepository driverProfileRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DriverDocumentRepository driverDocumentRepository;
    @Autowired EmergencyContactRepository emergencyContactRepository;
    @Autowired SmileIdCheckRepository smileIdCheckRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean VerifiedIdentityService verifiedIdentityService;
    @MockBean NinLookupService ninLookupService;
    @MockBean DriverIdentity driverIdentity;
    @MockBean IdentityServiceClient identityServiceClient;
    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean CloudinaryService cloudinaryService;

    @BeforeEach
    void cleanSlate() {
        when(ninLookupService.view(any())).thenReturn(new org.coryd.profile.dto.DriverNinView(true, "MATCH", null, null, 3));
        when(kafkaTemplate.send(anyString(), any())).thenReturn(new CompletableFuture<>());
        smileIdCheckRepository.deleteAll();
        driverDocumentRepository.deleteAll();
        vehicleRepository.deleteAll();
        emergencyContactRepository.deleteAll();
        driverProfileRepository.deleteAll();
        driverIndexRepository.deleteAll();
    }

    // ---- kept current by saves ------------------------------------------------------------------

    @Test
    void savingADriverKeepsTheirRowCurrentWithNoExtraCall() {
        submitAll("aaaa-1", "Emeka", "Okafor", "LND-441-AA");

        DriverIndexView row = index.driver("aaaa-1");
        assertThat(row.firstName()).isEqualTo("Emeka");
        assertThat(row.plateNumber()).isEqualTo("LND-441-AA");
        assertThat(row.vehicleMake()).isEqualTo("Toyota");
        assertThat(row.verificationStatus()).isEqualTo(VerificationStatus.IN_REVIEW);
        assertThat(row.status()).isEqualTo(DriverDirectoryStatus.PENDING);
        assertThat(row.joinedAt()).isNotNull();

        approveAll("aaaa-1");
        assertThat(index.driver("aaaa-1").status()).isEqualTo(DriverDirectoryStatus.VERIFIED);

        decide("aaaa-1", new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.REJECTED, "Fake"));
        driverDocumentService.create("aaaa-1", document(DocumentType.INSURANCE, "doc-new"));
        assertThat(index.driver("aaaa-1").status()).as("re-uploaded what ops rejected").isEqualTo(DriverDirectoryStatus.RESUBMITTED);
    }

    @Test
    void aDeletedDriverLeavesTheIndexAndAnUnknownOneIsNotFound() {
        submitAll("aaaa-1", "Emeka", "Okafor", "LND-441-AA");
        driverDocumentRepository.deleteAll();
        vehicleRepository.deleteAll();
        driverProfileRepository.deleteAll();

        assertThat(driverIndexRepository.findById("aaaa-1")).isEmpty();
        assertThatThrownBy(() -> index.driver("aaaa-1")).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- other services' numbers ------------------------------------------------------------------

    @Test
    void tripAndPaymentNumbersLandAndAnOlderOneNeverOverwritesANewerOne() {
        submitAll("aaaa-1", "Emeka", "Okafor", "LND-441-AA");
        Instant now = Instant.now();

        index.applyStats(stats("aaaa-1", 213, 4.88, 91, now));
        index.applyStats(stats("aaaa-1", 1, 1.0, 10, now.minus(1, ChronoUnit.HOURS)));
        index.applyEarnings(new DriverEarningsChangedEvent("aaaa-1", new BigDecimal("2000.00"), new BigDecimal("1900.00"), now));
        index.applyEarnings(new DriverEarningsChangedEvent("aaaa-1", BigDecimal.ONE, BigDecimal.ONE, now.minus(1, ChronoUnit.HOURS)));

        DriverIndexView row = index.driver("aaaa-1");
        assertThat(row.trips()).isEqualTo(213);
        assertThat(row.ratingAvg()).isEqualTo(4.88);
        assertThat(row.reliabilityPercent()).isEqualTo(91);
        assertThat(row.reportsFiled()).isEqualTo(2);
        assertThat(row.earningsNet()).isEqualByComparingTo("1900.00");
        assertThat(row.earningsGross()).isEqualByComparingTo("2000.00");
    }

    @Test
    void numbersForARiderAreIgnoredAndForADriverNotYetIndexedTheyIndexThemFirst() {
        index.applyStats(stats("rider-1", 3, 5.0, 100, Instant.now()));
        assertThat(driverIndexRepository.findById("rider-1")).isEmpty();

        submitAll("aaaa-1", "Emeka", "Okafor", "LND-441-AA");
        driverIndexRepository.deleteAll();
        index.applyStats(stats("aaaa-1", 7, 4.0, 80, Instant.now()));
        assertThat(index.driver("aaaa-1").trips()).isEqualTo(7);
        assertThat(index.driver("aaaa-1").firstName()).isEqualTo("Emeka");
    }

    @Test
    void suspensionMovesADriverToSuspendedAndReinstatingBringsThemBack() {
        submitAll("aaaa-1", "Emeka", "Okafor", "LND-441-AA");
        approveAll("aaaa-1");

        index.applySuspension("aaaa-1", Instant.now(), "Threatened a rider");
        DriverIndexView suspended = index.driver("aaaa-1");
        assertThat(suspended.status()).isEqualTo(DriverDirectoryStatus.SUSPENDED);
        assertThat(suspended.suspensionReason()).isEqualTo("Threatened a rider");
        assertThat(suspended.verificationStatus()).as("kept, to show alongside").isEqualTo(VerificationStatus.APPROVED);

        index.applySuspension("aaaa-1", null, null);
        assertThat(index.driver("aaaa-1").status()).isEqualTo(DriverDirectoryStatus.VERIFIED);
        assertThat(index.driver("aaaa-1").suspensionReason()).isNull();
        index.applySuspension("rider-1", Instant.now(), "x");
        assertThat(driverIndexRepository.findById("rider-1")).isEmpty();
    }

    // ---- the directory query ----------------------------------------------------------------------

    @Test
    void theDirectoryFiltersSortsAndPagesInTheDatabase() {
        seedFour();

        assertThat(names(index.directory(null, null, null, null, 0, 20).content())).as("newest sign-up first")
                .containsExactly("Kingsley Nwosu", "Fatima Bello", "Aisha Lawal", "Emeka Okafor");
        assertThat(names(index.directory(DriverDirectoryStatus.SUSPENDED, null, null, null, 0, 20).content())).containsExactly("Aisha Lawal");
        assertThat(names(index.directory(null, null, DriverDirectorySort.EARNINGS, Sort.Direction.DESC, 0, 20).content()))
                .as("no earnings yet goes last").containsExactly("Emeka Okafor", "Aisha Lawal", "Fatima Bello", "Kingsley Nwosu");
        assertThat(names(index.directory(null, null, DriverDirectorySort.EARNINGS, Sort.Direction.ASC, 0, 20).content()))
                .as("still last the other way").containsExactly("Fatima Bello", "Aisha Lawal", "Emeka Okafor", "Kingsley Nwosu");
        assertThat(names(index.directory(null, null, DriverDirectorySort.NAME, Sort.Direction.ASC, 0, 20).content()))
                .containsExactly("Aisha Lawal", "Emeka Okafor", "Fatima Bello", "Kingsley Nwosu");

        var page = index.directory(null, null, DriverDirectorySort.TRIPS, Sort.Direction.DESC, 1, 2);
        assertThat(page.totalElements()).isEqualTo(4);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.last()).isTrue();
        assertThat(names(page.content())).as("283, 213 on page 1; 100, then no trips yet").containsExactly("Aisha Lawal", "Kingsley Nwosu");
    }

    @Test
    void searchFindsByNameRefPhoneOrPlateAndCountsFollowIt() {
        seedFour();

        for (String search : new String[]{"fatima", "BELLO", "D-BBBB", "bbbb", "0801111", "kja100", "KJA-100"}) {
            assertThat(names(index.directory(null, search, null, null, 0, 20).content())).as(search).containsExactly("Fatima Bello");
        }
        assertThat(index.directory(null, "50%", null, null, 0, 20).content()).as("LIKE wildcards are literal").isEmpty();
        assertThat(index.directory(null, "080", null, null, 0, 20).content()).as("phone needs 4+ digits").isEmpty();

        var counts = index.counts(null);
        assertThat(counts.all()).isEqualTo(4);
        assertThat(counts.verified()).isEqualTo(1);
        assertThat(counts.pending()).isEqualTo(1);
        assertThat(counts.resubmitted()).isEqualTo(1);
        assertThat(counts.suspended()).isEqualTo(1);
        assertThat(index.counts("okafor").all()).isEqualTo(1);
    }

    @Test
    void lastTripAtComesWithTheTripNumbers() {
        submitAll("aaaa-1", "Emeka", "Okafor", "LND-441-AA");
        Instant now = Instant.now();
        Instant trip = now.minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        index.applyStats(stats("aaaa-1", 5, trip, now));
        assertThat(index.driver("aaaa-1").lastTripAt()).isEqualTo(trip);
        index.applyStats(stats("aaaa-1", 4, now.minus(9, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS)));
        assertThat(index.driver("aaaa-1").lastTripAt()).as("an older message is ignored").isEqualTo(trip);
        index.applyStats(stats("aaaa-1", 5, 4.0, 90, now.plusSeconds(1)));
        assertThat(index.driver("aaaa-1").lastTripAt()).as("a sender without lastTripAt leaves it alone").isEqualTo(trip);
        index.applyStats(stats("aaaa-1", 0, null, now.plusSeconds(2)));
        assertThat(index.driver("aaaa-1").lastTripAt()).isNull();
    }

    @Test
    void sortingByLastTripPutsDriversWhoNeverDroveLastAndCountsTheActiveOnes() {
        seedFour();
        Instant now = Instant.now();
        index.applyStats(stats("aaaa1111-1", 213, now.minus(2, ChronoUnit.DAYS), now.plusSeconds(1)));
        index.applyStats(stats("bbbb2222-2", 283, now.minus(8, ChronoUnit.DAYS), now.plusSeconds(1)));
        index.applyStats(stats("dddd4444-4", 100, now.minus(1, ChronoUnit.HOURS), now.plusSeconds(1)));

        assertThat(names(index.directory(null, null, DriverDirectorySort.LAST_TRIP, Sort.Direction.DESC, 0, 20).content()))
                .containsExactly("Aisha Lawal", "Emeka Okafor", "Fatima Bello", "Kingsley Nwosu");
        assertThat(names(index.directory(null, null, DriverDirectorySort.LAST_TRIP, Sort.Direction.ASC, 0, 20).content()))
                .as("never drove still last").containsExactly("Fatima Bello", "Emeka Okafor", "Aisha Lawal", "Kingsley Nwosu");
        assertThat(index.counts(null).activeLast7Days()).isEqualTo(2);
        assertThat(index.counts("bello").activeLast7Days()).as("same search as the table").isZero();
    }

    private static DriverStatsChangedEvent stats(String driverId, long trips, Instant lastTripAt, Instant at) {
        return DriverStatsChangedEvent.builder().driverId(driverId).trips(trips).ratingCount(0).reportsFiled(0)
                .lastTripAt(lastTripAt).computedAt(at).build();
    }

    // ---- startup backfill and nightly rebuild -----------------------------------------------------

    @Test
    void theBackfillIndexesDriversMissingFromItWithTheirPhoneSuspensionAndSignUpDate() {
        submitAll("aaaa-1", "Emeka", "Okafor", "LND-441-AA");
        submitAll("bbbb-2", "Fatima", "Bello", "KJA-100-BC");
        driverIndexRepository.deleteAll();
        Instant joined = Instant.parse("2026-01-12T09:00:00Z");
        when(identityServiceClient.contacts(any())).thenReturn(List.of(
                new IdentityServiceClient.Contact("aaaa-1", "08053636321", null, null, joined),
                new IdentityServiceClient.Contact("bbbb-2", "08011112222", Instant.now(), "Unsafe driving", joined)));

        assertThat(jobs.backfill()).isEqualTo(2);
        assertThat(jobs.backfill()).as("nothing left to do").isZero();

        assertThat(index.driver("aaaa-1").phone()).isEqualTo("08053636321");
        assertThat(index.driver("aaaa-1").joinedAt()).isEqualTo(joined);
        assertThat(index.driver("bbbb-2").status()).isEqualTo(DriverDirectoryStatus.SUSPENDED);
    }

    @Test
    void theNightlyRebuildRepairsARowARawChangeLeftStale() {
        submitAll("aaaa-1", "Emeka", "Okafor", "LND-441-AA");
        jdbcTemplate.update("update profile.driver_profiles set first_name = 'Chukwuemeka' where user_id = 'aaaa-1'");
        assertThat(index.driver("aaaa-1").firstName()).isEqualTo("Emeka");

        assertThat(jobs.rebuildAll()).isEqualTo(1);

        assertThat(index.driver("aaaa-1").firstName()).isEqualTo("Chukwuemeka");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** Emeka verified (oldest), Aisha verified then suspended, Fatima resubmitted, Kingsley pending (newest, no numbers). */
    private void seedFour() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "LND-441-AA");
        approveAll("aaaa1111-1");
        submitAll("dddd4444-4", "Aisha", "Lawal", "EKY-9-ZZ");
        approveAll("dddd4444-4");
        index.applySuspension("dddd4444-4", Instant.now(), "Threatened a rider");
        submitAll("bbbb2222-2", "Fatima", "Bello", "KJA-100-BC");
        decide("bbbb2222-2", new ItemDecision(ReviewableItem.VEHICLE, VerificationStatus.REJECTED, "Plate unreadable"));
        vehicleService.update(vehicleRepository.findByDriverId("bbbb2222-2").get(0).getId(), "bbbb2222-2", vehicle("KJA-100-BC"));
        driverProfileService.updateMine("cccc3333-3", new UpdateDriverProfileRequest("Kingsley", "Nwosu", null, null, LocalDate.of(1990, 1, 1),
                null, null, "LAG12345678", null, null));
        joined("aaaa1111-1", "2026-01-12T09:00:00Z");
        joined("dddd4444-4", "2026-02-01T09:00:00Z");
        joined("bbbb2222-2", "2026-03-01T09:00:00Z");
        joined("cccc3333-3", "2026-05-01T09:00:00Z");
        Instant now = Instant.now();
        index.applyStats(stats("aaaa1111-1", 213, 4.88, 91, now));
        index.applyStats(stats("bbbb2222-2", 283, 4.5, 67, now));
        index.applyStats(stats("dddd4444-4", 100, 4.9, 97, now));
        index.applyEarnings(new DriverEarningsChangedEvent("aaaa1111-1", new BigDecimal("2000"), new BigDecimal("1900"), now));
        index.applyEarnings(new DriverEarningsChangedEvent("bbbb2222-2", new BigDecimal("100"), new BigDecimal("95"), now));
        index.applyEarnings(new DriverEarningsChangedEvent("dddd4444-4", new BigDecimal("200"), new BigDecimal("190"), now));
        jdbcTemplate.update("update profile.driver_index set phone = '08011112222', phone_digits = '08011112222' where driver_id = 'bbbb2222-2'");
    }

    private void joined(String driverId, String at) {
        jdbcTemplate.update("update profile.driver_index set joined_at = ?::timestamp where driver_id = ?",
                at.replace("T", " ").replace("Z", ""), driverId);
    }

    private static DriverStatsChangedEvent stats(String driverId, long trips, double rating, int reliability, Instant at) {
        return new DriverStatsChangedEvent(driverId, trips, rating, 10, reliability, 2, at);
    }

    private static List<String> names(List<DriverIndexView> rows) {
        return rows.stream().map(r -> r.firstName() + " " + r.lastName()).toList();
    }

    private void submitAll(String driverId, String first, String last, String plate) {
        driverProfileService.updateMine(driverId, new UpdateDriverProfileRequest(first, last, null, null, LocalDate.of(1990, 1, 1),
                "https://img/licence-" + driverId + ".jpg", "licence-" + driverId, "LAG12345678", null, null));
        vehicleService.register(driverId, driverId, vehicle(plate));
        driverDocumentService.create(driverId, document(DocumentType.INSURANCE, "doc-ins-" + driverId));
        driverDocumentService.create(driverId, document(DocumentType.ROADWORTHINESS_CERT, "doc-rw-" + driverId));
    }

    private static VehicleRegistrationRequest vehicle(String plate) {
        return new VehicleRegistrationRequest("Toyota", "Corolla", 2019, "Black", plate, 4,
                "https://img/ext.jpg", "ext-1", "https://img/int.jpg", "int-1", true, true);
    }

    private static CreateDriverDocumentRequest document(DocumentType type, String publicId) {
        return new CreateDriverDocumentRequest(type, "https://img/" + publicId + ".jpg", publicId, LocalDate.now().plusYears(1), null);
    }

    private void approveAll(String driverId) {
        decide(driverId, new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.VEHICLE, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.ROADWORTHINESS_CERT, VerificationStatus.APPROVED, null));
    }

    private void decide(String driverId, ItemDecision... decisions) {
        verification.decide(driverId, UserRole.OPS_ADMIN, new VerificationDecisionBatchRequest(List.of(decisions), "Ops Tester"));
    }
}
