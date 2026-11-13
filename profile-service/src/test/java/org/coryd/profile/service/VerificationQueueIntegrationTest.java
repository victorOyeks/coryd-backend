package org.coryd.profile.service;

import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.dto.VerificationQueueItem;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
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

/** The ops verification queue and decisions against a throwaway Postgres container - never a shared or real database. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({VerificationAggregationService.class, DriverProfileService.class, VehicleService.class, DriverDocumentService.class,
        EmergencyContactService.class, RiderProfileService.class, ReplacedFiles.class, DriverIndexService.class, DriverIndexer.class})
class VerificationQueueIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired VerificationAggregationService verification;
    @Autowired DriverProfileService driverProfileService;
    @Autowired VehicleService vehicleService;
    @Autowired DriverDocumentService driverDocumentService;
    @Autowired DriverProfileRepository driverProfileRepository;
    @Autowired DriverIndexService driverIndexService;
    @Autowired org.coryd.profile.repository.DriverIndexRepository driverIndexRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DriverDocumentRepository driverDocumentRepository;
    @Autowired EmergencyContactRepository emergencyContactRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;

    @MockBean VerifiedIdentityService verifiedIdentityService;
    @MockBean NinLookupService ninLookupService;
    @MockBean DriverIdentity driverIdentity;
    @MockBean IdentityServiceClient identityServiceClient;
    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean CloudinaryService cloudinaryService;

    @BeforeEach
    void cleanSlate() throws Exception {
        org.mockito.Mockito.when(ninLookupService.view(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.coryd.profile.dto.DriverNinView(true, "MATCH", null, null, 3));
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL())
                    .as("tests must only ever run against the Testcontainers database")
                    .isEqualTo(postgres.getJdbcUrl());
        }
        driverDocumentRepository.deleteAll();
        vehicleRepository.deleteAll();
        emergencyContactRepository.deleteAll();
        driverProfileRepository.deleteAll();
        driverIndexRepository.deleteAll();
        when(kafkaTemplate.send(anyString(), any())).thenReturn(new CompletableFuture<>());
    }

    @Test
    void tabsSeparateFreshPartlyDecidedAndResubmittedDrivers() {
        submitAll("fresh-1", "Ada", "Obi");
        submitAll("partly-1", "Tunde", "Bakare");
        decide("partly-1", approve(ReviewableItem.GOVERNMENT_ID));
        submitAll("resub-1", "Ngozi", "Eze");
        decide("resub-1", reject(ReviewableItem.VEHICLE, "Plate not readable"));
        vehicleService.update(vehicleRepository.findByDriverId("resub-1").get(0).getId(), "resub-1", vehicle("RSB-123"));

        assertThat(tabOf("fresh-1")).isEqualTo(VerificationQueueTab.PENDING);
        assertThat(tabOf("partly-1")).isEqualTo(VerificationQueueTab.IN_REVIEW);
        assertThat(tabOf("resub-1")).isEqualTo(VerificationQueueTab.RESUBMITTED);

        var counts = verification.queueCounts(UserRole.OPS_ADMIN, null);
        assertThat(counts.all()).isEqualTo(3);
        assertThat(counts.pending()).isEqualTo(1);
        assertThat(counts.inReview()).isEqualTo(1);
        assertThat(counts.resubmitted()).isEqualTo(1);
        assertThat(verification.queue(UserRole.OPS_ADMIN, VerificationQueueTab.RESUBMITTED, null, PageRequest.of(0, 20)).content())
                .extracting(VerificationQueueItem::driverId).containsExactly("resub-1");
    }

    @Test
    void longestWaitingDriverIsFirstAndEditsWhileWaitingKeepTheirPlace() {
        submitAll("early-1", "Ada", "Obi");
        submitAll("later-1", "Tunde", "Bakare");
        backdate("early-1", Instant.now().minus(5, ChronoUnit.HOURS));
        Instant before = verification.getStatus("early-1").waitingSince();

        vehicleService.update(vehicleRepository.findByDriverId("early-1").get(0).getId(), "early-1", vehicle("EDT-999"));

        var page = verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(0, 20));
        assertThat(page.content()).extracting(VerificationQueueItem::driverId).containsExactly("early-1", "later-1");
        assertThat(verification.getStatus("early-1").waitingSince()).isEqualTo(before);
    }

    @Test
    void queueSearchesByNameOrDriverRefAndPages() {
        submitAll("4f2a91c0-0000-0000-0000-000000000001", "Emeka", "Okafor");
        submitAll("9b1c77d0-0000-0000-0000-000000000002", "Fatima", "Bello");

        assertThat(verification.queue(UserRole.OPS_ADMIN, null, "okafor", PageRequest.of(0, 20)).content())
                .extracting(VerificationQueueItem::lastName).containsExactly("Okafor");
        assertThat(verification.queue(UserRole.OPS_ADMIN, null, "D-9B1C77", PageRequest.of(0, 20)).content())
                .extracting(VerificationQueueItem::firstName).containsExactly("Fatima");
        var firstPage = verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(0, 1));
        assertThat(firstPage.content()).hasSize(1);
        assertThat(firstPage.totalElements()).isEqualTo(2);
        assertThat(firstPage.last()).isFalse();
    }

    @Test
    void everyRejectionNeedsItsOwnReason() {
        submitAll("d-1", "Ada", "Obi");

        assertThatThrownBy(() -> decide("d-1", approve(ReviewableItem.GOVERNMENT_ID), reject(ReviewableItem.VEHICLE, " ")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("reason for rejecting the vehicle registration");
        assertThat(verification.getStatus("d-1").governmentId().status())
                .as("nothing is applied when the batch is invalid").isEqualTo(VerificationStatus.IN_REVIEW);

        decide("d-1", reject(ReviewableItem.VEHICLE, "Plate not readable"), reject(ReviewableItem.INSURANCE, "Certificate expired"));
        var status = verification.getStatus("d-1");
        assertThat(status.vehicleRegistration().rejectionReason()).isEqualTo("Plate not readable");
        assertThat(status.documents()).filteredOn(d -> d.type() == DocumentType.INSURANCE)
                .extracting(d -> d.rejectionReason()).containsExactly("Certificate expired");
    }

    @Test
    void decisionsAreApproveOrRejectOnceEach() {
        submitAll("d-1", "Ada", "Obi");

        assertThatThrownBy(() -> decide("d-1", new ItemDecision(ReviewableItem.VEHICLE, VerificationStatus.IN_REVIEW, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> decide("d-1", approve(ReviewableItem.VEHICLE), reject(ReviewableItem.VEHICLE, "x")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> verification.queue(UserRole.DRIVER, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void reviewShowsTheDriverAndWhenEachItemWasSubmitted() {
        submitAll("d-1", "Ada", "Obi");

        var status = verification.getStatus("d-1");

        assertThat(status.driver().firstName()).isEqualTo("Ada");
        assertThat(status.driver().licenceNumber()).isEqualTo("LAG12345678");
        assertThat(status.driver().dateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(status.masked().driver().licenceNumber()).as("what ops sees").isNull();
        assertThat(status.masked().driver().dateOfBirth()).isNull();
        assertThat(status.masked().driver().licenceNumberMasked()).isEqualTo("\u2022\u2022\u2022\u2022\u2022\u2022\u20225678");
        assertThat(status.masked().driver().birthYear()).isEqualTo(1990);
        assertThat(status.governmentId().submittedAt()).isNotNull();
        assertThat(status.vehicleRegistration().submittedAt()).isNotNull();
        assertThat(status.documents()).allSatisfy(d -> assertThat(d.submittedAt()).isNotNull());
        assertThat(status.waitingSince()).isNotNull();
    }

    @Test
    void decidingAResubmittedItemClearsTheFlag() {
        submitAll("d-1", "Ada", "Obi");
        decide("d-1", reject(ReviewableItem.INSURANCE, "Blurry"));
        driverDocumentService.create("d-1", document(DocumentType.INSURANCE));
        assertThat(verification.getStatus("d-1").documents()).filteredOn(d -> d.type() == DocumentType.INSURANCE)
                .extracting(d -> d.resubmitted()).containsExactly(true);

        decide("d-1", approve(ReviewableItem.INSURANCE));

        assertThat(verification.getStatus("d-1").documents()).filteredOn(d -> d.type() == DocumentType.INSURANCE)
                .extracting(d -> d.resubmitted()).containsExactly(false);
    }

    @Test
    void queueAndReviewPageShowTheSameVehicleForADriverWithTwo() {
        submitAll("d-1", "Ada", "Obi");
        decide("d-1", approve(ReviewableItem.VEHICLE));
        vehicleService.register("d-1", "d-1", vehicle("SECOND-1"));

        var row = verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(0, 20)).content().get(0);
        var vehicleEntry = row.items().stream().filter(e -> e.item() == ReviewableItem.VEHICLE).findFirst().orElseThrow();
        var status = verification.getStatus("d-1");

        assertThat(status.vehicleRegistration().vehicle().plateNumber()).isEqualTo("P-d-1");
        assertThat(vehicleEntry.status()).isEqualTo(status.vehicleRegistration().status()).isEqualTo(VerificationStatus.APPROVED);
    }

    // ---- helpers

    private void submitAll(String driverId, String first, String last) {
        driverProfileService.updateMine(driverId, new UpdateDriverProfileRequest(first, last, null, null, LocalDate.of(1990, 1, 1),
                "https://img/licence-" + driverId + ".jpg", "licence-" + driverId, "LAG12345678", null, null));
        vehicleService.register(driverId, driverId, vehicle("P-" + driverId.substring(0, Math.min(8, driverId.length()))));
        driverDocumentService.create(driverId, document(DocumentType.INSURANCE));
        driverDocumentService.create(driverId, document(DocumentType.ROADWORTHINESS_CERT));
    }

    private static VehicleRegistrationRequest vehicle(String plate) {
        return new VehicleRegistrationRequest("Toyota", "Corolla", 2019, "Black", plate, 4,
                "https://img/ext.jpg", "ext-1", "https://img/int.jpg", "int-1", true, true);
    }

    private static CreateDriverDocumentRequest document(DocumentType type) {
        return new CreateDriverDocumentRequest(type, "https://img/" + type + ".jpg", "doc-" + type, LocalDate.now().plusYears(1), null);
    }

    private void decide(String driverId, ItemDecision... decisions) {
        verification.decide(driverId, UserRole.OPS_ADMIN, new VerificationDecisionBatchRequest(List.of(decisions), "Ops Tester"));
    }

    private static ItemDecision approve(ReviewableItem item) {
        return new ItemDecision(item, VerificationStatus.APPROVED, null);
    }

    private static ItemDecision reject(ReviewableItem item, String reason) {
        return new ItemDecision(item, VerificationStatus.REJECTED, reason);
    }

    private VerificationQueueTab tabOf(String driverId) {
        return verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(0, 50)).content().stream()
                .filter(row -> row.driverId().equals(driverId)).findFirst().orElseThrow().tab();
    }

    private void backdate(String driverId, Instant when) {
        Timestamp at = Timestamp.from(when);
        jdbcTemplate.update("update profile.driver_profiles set government_id_submitted_at = ? where user_id = ?", at, driverId);
        jdbcTemplate.update("update profile.vehicles set submitted_at = ? where driver_id = ?", at, driverId);
        jdbcTemplate.update("update profile.driver_documents set submitted_at = ? where driver_id = ?", at, driverId);
        driverIndexService.refresh(driverId); // raw SQL skips the save hooks that keep the index current
    }
}
