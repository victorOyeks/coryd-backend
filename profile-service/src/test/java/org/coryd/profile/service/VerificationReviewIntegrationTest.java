package org.coryd.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.ReviewMark;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationQueueSort;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.profile.dto.ReviewDecisionRequest;
import org.coryd.profile.dto.ReviewMarkRequest;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.dto.VerificationQueueItem;
import org.coryd.profile.dto.VerificationReviewResponse;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.coryd.profile.repository.VerificationReviewMarkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The ops review screen: Verified / Flagged marks on each document, Reset, Approve Driver, Reject, the audit log and
 * the queue's sortable columns - against a throwaway Postgres container.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({VerificationAggregationService.class, VerificationReviewService.class, DriverProfileService.class, VehicleService.class,
        DriverDocumentService.class, EmergencyContactService.class, RiderProfileService.class, ReplacedFiles.class,
        DriverIndexService.class, DriverIndexer.class})
class VerificationReviewIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OPS = "Ada Ops";

    @Autowired VerificationAggregationService verification;
    @Autowired VerificationReviewService review;
    @Autowired DriverProfileService driverProfileService;
    @Autowired VehicleService vehicleService;
    @Autowired DriverDocumentService driverDocumentService;
    @Autowired DriverProfileRepository driverProfileRepository;
    @Autowired DriverIndexService driverIndexService;
    @Autowired org.coryd.profile.repository.DriverIndexRepository driverIndexRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DriverDocumentRepository driverDocumentRepository;
    @Autowired EmergencyContactRepository emergencyContactRepository;
    @Autowired VerificationReviewMarkRepository markRepository;
    @Autowired VerificationDecisionLogRepository decisionLogRepository;
    @Autowired VerificationCheckRepository verificationCheckRepository;
    @Autowired org.coryd.profile.repository.SmileIdCheckRepository smileIdCheckRepository;
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
        when(ninLookupService.view(any())).thenReturn(new org.coryd.profile.dto.DriverNinView(true, "MATCH", null, null, 3));
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
        markRepository.deleteAll();
        smileIdCheckRepository.deleteAll();
        decisionLogRepository.deleteAll();
        verificationCheckRepository.deleteAll();
        driverDocumentRepository.deleteAll();
        vehicleRepository.deleteAll();
        emergencyContactRepository.deleteAll();
        driverProfileRepository.deleteAll();
        driverIndexRepository.deleteAll();
        when(kafkaTemplate.send(anyString(), any())).thenReturn(new CompletableFuture<>());
    }

    // ---- marks --------------------------------------------------------------------------------

    @Test
    void eachDocumentStartsUnreviewedAndCanBeVerifiedFlaggedAndResetToDefault() {
        submitAll("d-1", "Emeka", "Okafor");
        assertThat(review.review("d-1", UserRole.OPS_ADMIN).items()).allSatisfy(i -> {
            assertThat(i.mark()).as("Unreviewed").isNull();
            assertThat(i.status()).isEqualTo(VerificationStatus.IN_REVIEW);
        });

        review.mark("d-1", ReviewableItem.GOVERNMENT_ID, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), "fallback", UserRole.OPS_ADMIN);
        VerificationReviewResponse flagged = review.mark("d-1", ReviewableItem.VEHICLE,
                new ReviewMarkRequest(ReviewMark.FLAGGED, " Blurry document ", null), "Bola Ops", UserRole.OPS_ADMIN);

        assertThat(item(flagged, ReviewableItem.GOVERNMENT_ID).mark()).isEqualTo(ReviewMark.VERIFIED);
        assertThat(item(flagged, ReviewableItem.GOVERNMENT_ID).markedBy()).isEqualTo(OPS);
        assertThat(item(flagged, ReviewableItem.VEHICLE).mark()).isEqualTo(ReviewMark.FLAGGED);
        assertThat(item(flagged, ReviewableItem.VEHICLE).note()).isEqualTo("Blurry document");
        assertThat(item(flagged, ReviewableItem.VEHICLE).markedBy()).as("the signed-in admin when none is given").isEqualTo("Bola Ops");
        assertThat(item(flagged, ReviewableItem.VEHICLE).markedAt()).isNotNull();
        assertThat(item(flagged, ReviewableItem.VEHICLE).label()).isEqualTo("Vehicle registration");
        assertThat(flagged.canReject()).isTrue();

        VerificationReviewResponse reset = review.reset("d-1", ReviewableItem.VEHICLE, UserRole.OPS_ADMIN);
        assertThat(item(reset, ReviewableItem.VEHICLE).mark()).isNull();
        assertThat(reset.canReject()).isFalse();
        assertThat(review.reset("d-1", ReviewableItem.VEHICLE, UserRole.OPS_ADMIN).items()).as("resetting twice is fine").hasSize(4);
    }

    @Test
    void markingAgainReplacesTheMark() {
        submitAll("d-1", "Emeka", "Okafor");
        review.mark("d-1", ReviewableItem.INSURANCE, new ReviewMarkRequest(ReviewMark.FLAGGED, "Expired", OPS), null, UserRole.OPS_ADMIN);
        review.mark("d-1", ReviewableItem.INSURANCE, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);

        assertThat(markRepository.findByDriverId("d-1")).singleElement().satisfies(m -> {
            assertThat(m.getMark()).isEqualTo(ReviewMark.VERIFIED);
            assertThat(m.getNote()).isNull();
        });
    }

    @Test
    void aFlagNeedsANoteTheDriverCanBeShown() {
        submitAll("d-1", "Emeka", "Okafor");
        assertThatThrownBy(() -> review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.FLAGGED, "  ", OPS), null, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Add a note");
        assertThatThrownBy(() -> review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.FLAGGED, "x".repeat(256), OPS), null, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("255");
        assertThat(markRepository.findAll()).isEmpty();
    }

    @Test
    void anItemWaitingOrApprovedCanBeMarkedButNotOneRejectedOrNeverUploaded() {
        submitAll("d-1", "Emeka", "Okafor");
        decide("d-1", new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.REJECTED, "Expired"));
        driverProfileService.updateMine("d-2", new UpdateDriverProfileRequest("Fatima", "Bello", null, null, LocalDate.of(1990, 1, 1),
                "https://img/licence-d-2.jpg", "licence-d-2", "LAG12345678", null, null));

        VerificationReviewResponse approved = review.mark("d-1", ReviewableItem.GOVERNMENT_ID,
                new ReviewMarkRequest(ReviewMark.FLAGGED, "Not the driver in the photo", OPS), null, UserRole.OPS_ADMIN);
        assertThat(item(approved, ReviewableItem.GOVERNMENT_ID).mark()).as("an approval can be taken back").isEqualTo(ReviewMark.FLAGGED);
        assertThatThrownBy(() -> review.mark("d-1", ReviewableItem.INSURANCE, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN))
                .as("waiting for the driver to re-upload").isInstanceOf(BadRequestException.class).hasMessageContaining("rejected");
        assertThatThrownBy(() -> review.mark("d-2", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN))
                .as("never uploaded").isInstanceOf(BadRequestException.class).hasMessageContaining("pending");
    }

    @Test
    void aMarkStopsCountingOnceTheDriverReuploads() {
        submitAll("d-1", "Emeka", "Okafor");
        review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.FLAGGED, "Plate unreadable", OPS), null, UserRole.OPS_ADMIN);
        jdbcTemplate.update("update profile.vehicles set submitted_at = submitted_at + interval '1 minute' where driver_id = 'd-1'");

        VerificationReviewResponse after = review.review("d-1", UserRole.OPS_ADMIN);

        assertThat(item(after, ReviewableItem.VEHICLE).mark()).isNull();
        assertThat(after.canReject()).isFalse();
        assertThatThrownBy(() -> review.reject("d-1", null, OPS, UserRole.OPS_ADMIN)).isInstanceOf(BadRequestException.class);
    }

    // ---- Approve Driver ------------------------------------------------------------------------

    @Test
    void approveDriverNeedsEveryDocumentReviewedAndSaysWhatsLeft() {
        submitAll("d-1", "Emeka", "Okafor");
        assertThat(review.review("d-1", UserRole.OPS_ADMIN).approveBlockedReason())
                .isEqualTo("Not reviewed yet: Driver's licence, Vehicle registration, Insurance certificate, Roadworthiness certificate");
        verifyAll("d-1", ReviewableItem.GOVERNMENT_ID, ReviewableItem.VEHICLE, ReviewableItem.INSURANCE);

        VerificationReviewResponse state = review.review("d-1", UserRole.OPS_ADMIN);
        assertThat(state.canApprove()).isFalse();
        assertThat(state.approveBlockedReason()).isEqualTo("Not reviewed yet: Roadworthiness certificate");
        assertThatThrownBy(() -> review.approve("d-1", null, OPS, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class).hasMessage("Not reviewed yet: Roadworthiness certificate");
    }

    @Test
    void approveDriverApprovesEverythingKeepsTheNoteInTheAuditLogAndClearsTheMarks() {
        submitAll("d-1", "Emeka", "Okafor");
        verifyAll("d-1", ReviewableItem.values());
        review.mark("d-1", ReviewableItem.INSURANCE, new ReviewMarkRequest(ReviewMark.VERIFIED, "Valid till 2027", OPS), null, UserRole.OPS_ADMIN);
        assertThat(review.review("d-1", UserRole.OPS_ADMIN).canApprove()).isTrue();

        var status = review.approve("d-1", new ReviewDecisionRequest("All clear", null), OPS, UserRole.OPS_ADMIN);

        assertThat(status.overallStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(markRepository.findByDriverId("d-1")).isEmpty();
        var history = review.history("d-1", UserRole.OPS_ADMIN);
        assertThat(history).hasSize(4).allSatisfy(h -> {
            assertThat(h.status()).isEqualTo(VerificationStatus.APPROVED);
            assertThat(h.decidedBy()).isEqualTo(OPS);
            assertThat(h.decidedAt()).isNotNull();
        });
        assertThat(history).filteredOn(h -> h.item() == ReviewableItem.INSURANCE).singleElement()
                .satisfies(h -> assertThat(h.note()).as("the document's own note wins").isEqualTo("Valid till 2027"));
        assertThat(history).filteredOn(h -> h.item() == ReviewableItem.VEHICLE).singleElement()
                .satisfies(h -> assertThat(h.note()).isEqualTo("All clear"));
        assertThat(review.review("d-1", UserRole.OPS_ADMIN).approveBlockedReason()).isEqualTo("Nothing is waiting for review");
    }

    @Test
    void anItemAlreadyApprovedCountsTowardsApproveDriver() {
        submitAll("d-1", "Emeka", "Okafor");
        decide("d-1", new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.APPROVED, null));
        verifyAll("d-1", ReviewableItem.VEHICLE, ReviewableItem.INSURANCE, ReviewableItem.ROADWORTHINESS_CERT);

        assertThat(review.approve("d-1", null, OPS, UserRole.OPS_ADMIN).overallStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(review.history("d-1", UserRole.OPS_ADMIN)).hasSize(4);
    }

    @Test
    void approveDriverIsRefusedWithAFlagAMissingUploadOrARejectionAwaitingReupload() {
        submitAll("d-1", "Emeka", "Okafor");
        verifyAll("d-1", ReviewableItem.values());
        review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.FLAGGED, "Blurry", OPS), null, UserRole.OPS_ADMIN);
        assertThatThrownBy(() -> review.approve("d-1", null, OPS, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class).hasMessage("Flagged: Vehicle registration - reject, or reset the flag");

        driverProfileService.updateMine("d-2", new UpdateDriverProfileRequest("Fatima", "Bello", null, null, LocalDate.of(1990, 1, 1),
                "https://img/licence-d-2.jpg", "licence-d-2", "LAG12345678", null, null));
        verifyAll("d-2", ReviewableItem.GOVERNMENT_ID);
        assertThat(review.review("d-2", UserRole.OPS_ADMIN).approveBlockedReason())
                .isEqualTo("Not uploaded yet: Vehicle registration, Insurance certificate, Roadworthiness certificate");

        submitAll("d-3", "Kingsley", "Nwosu");
        decide("d-3", new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.REJECTED, "Expired"));
        verifyAll("d-3", ReviewableItem.GOVERNMENT_ID, ReviewableItem.VEHICLE, ReviewableItem.ROADWORTHINESS_CERT);
        assertThat(review.review("d-3", UserRole.OPS_ADMIN).approveBlockedReason())
                .isEqualTo("Waiting for the driver to re-upload: Insurance certificate");
    }

    // ---- Reject ------------------------------------------------------------------------------------

    @Test
    void rejectSendsTheFlagsAsReasonsApprovesTheVerifiedAndLeavesTheRestWaiting() {
        submitAll("d-1", "Emeka", "Okafor");
        review.mark("d-1", ReviewableItem.GOVERNMENT_ID, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);
        review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.FLAGGED, "Plate unreadable", OPS), null, UserRole.OPS_ADMIN);
        review.mark("d-1", ReviewableItem.INSURANCE, new ReviewMarkRequest(ReviewMark.FLAGGED, "Expired in August", OPS), null, UserRole.OPS_ADMIN);

        var status = review.reject("d-1", null, OPS, UserRole.OPS_ADMIN);

        assertThat(status.governmentId().status()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(status.vehicleRegistration().status()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(status.vehicleRegistration().rejectionReason()).isEqualTo("Plate unreadable");
        assertThat(status.documents()).filteredOn(d -> d.type() == DocumentType.INSURANCE).singleElement()
                .satisfies(d -> assertThat(d.rejectionReason()).isEqualTo("Expired in August"));
        assertThat(status.documents()).filteredOn(d -> d.type() == DocumentType.ROADWORTHINESS_CERT).singleElement()
                .satisfies(d -> assertThat(d.status()).as("not looked at - still waiting").isEqualTo(VerificationStatus.IN_REVIEW));
        assertThat(markRepository.findByDriverId("d-1")).isEmpty();
        assertThat(review.history("d-1", UserRole.OPS_ADMIN)).extracting(h -> h.item() + ":" + h.status() + ":" + h.note())
                .containsExactlyInAnyOrder("GOVERNMENT_ID:APPROVED:null", "VEHICLE:REJECTED:Plate unreadable", "INSURANCE:REJECTED:Expired in August");
    }

    @Test
    void rejectNeedsAtLeastOneFlag() {
        submitAll("d-1", "Emeka", "Okafor");
        review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);

        assertThatThrownBy(() -> review.reject("d-1", null, OPS, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Flag at least one");
        assertThat(verification.getStatus("d-1").vehicleRegistration().status()).isEqualTo(VerificationStatus.IN_REVIEW);
    }

    // ---- audit log -------------------------------------------------------------------------------

    @Test
    void everyDecisionIsLoggedNewestFirstIncludingTheOldDecisionsCallAndSmileIdsAutoApproval() {
        submitAll("d-1", "Emeka", "Okafor");
        decide("d-1", new ItemDecision(ReviewableItem.VEHICLE, VerificationStatus.REJECTED, "Wrong car"));
        decide("d-1", new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.APPROVED, null, "Checked with insurer"));
        verification.autoApproveGovernmentId("d-1", "Smile ID");

        var history = review.history("d-1", UserRole.OPS_ADMIN);

        assertThat(history).extracting(h -> h.item() + ":" + h.decidedBy() + ":" + h.note()).containsExactly(
                "GOVERNMENT_ID:Smile ID:null", "INSURANCE:Ops Tester:Checked with insurer", "VEHICLE:Ops Tester:Wrong car");
        assertThat(history.get(0).label()).isEqualTo("Driver's licence");
    }

    // ---- the queue's columns -------------------------------------------------------------------

    @Test
    void theQueueSortsByEachColumnBothWays() {
        submitAll("aaaa-1", "Zainab", "Umar");
        submitAll("bbbb-1", "Ada", "Obi");
        submitAll("cccc-1", "Musa", "Bello");
        jdbcTemplate.update("update profile.vehicles set submitted_at = submitted_at - interval '3 hours' where driver_id = 'cccc-1'");
        jdbcTemplate.update("update profile.vehicles set submitted_at = submitted_at - interval '2 hours' where driver_id = 'aaaa-1'");
        jdbcTemplate.update("update profile.vehicles set submitted_at = submitted_at - interval '1 hours' where driver_id = 'bbbb-1'");
        driverIndexService.refreshAll(List.of("aaaa-1", "bbbb-1", "cccc-1")); // raw SQL skips the save hooks

        assertThat(order(null, null)).as("longest waiting first, as before").containsExactly("cccc-1", "aaaa-1", "bbbb-1");
        assertThat(order(VerificationQueueSort.WAITING_SINCE, Sort.Direction.DESC)).containsExactly("bbbb-1", "aaaa-1", "cccc-1");
        assertThat(order(VerificationQueueSort.NAME, Sort.Direction.ASC)).containsExactly("bbbb-1", "cccc-1", "aaaa-1");
        assertThat(order(VerificationQueueSort.NAME, Sort.Direction.DESC)).containsExactly("aaaa-1", "cccc-1", "bbbb-1");
        assertThat(order(VerificationQueueSort.DRIVER_REF, Sort.Direction.ASC)).containsExactly("aaaa-1", "bbbb-1", "cccc-1");
        assertThat(order(VerificationQueueSort.SUBMITTED_AT, Sort.Direction.ASC)).containsExactly("cccc-1", "aaaa-1", "bbbb-1");
        assertThat(verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(1, 2), VerificationQueueSort.NAME, Sort.Direction.ASC)
                .content()).extracting(VerificationQueueItem::driverId).as("sorted before paging").containsExactly("aaaa-1");
    }

    @Test
    void aQueueRowCarriesItsSubmittedTimeAndEachDocumentsLabel() throws Exception {
        submitAll("d-1", "Emeka", "Okafor");
        VerificationQueueItem row = verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(0, 20)).content().get(0);

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(row);

        assertThat(row.submittedAt()).isNotNull();
        assertThat(json).contains("\"submittedAt\"").contains("\"label\":\"Driver's licence\"").contains("\"label\":\"Roadworthiness certificate\"");
    }

    @Test
    void theLicenceCheckCarriesSmileIdsJobReference() {
        submitAll("d-1", "Emeka", "Okafor");
        verificationCheckRepository.save(VerificationCheck.builder().driverId("d-1").checkType(VerificationCheckType.DRIVERS_LICENCE)
                .subjectKey("LAG12345678").outcome(VerificationCheckOutcome.NAME_MISMATCH).providerSummary("Name differs - job job-123")
                .providerReference("job-123").build());

        assertThat(verification.getStatus("d-1").providerChecks()).singleElement()
                .satisfies(c -> assertThat(c.providerReference()).isEqualTo("job-123"));
    }

    // ---- a licence still with Smile ID ----------------------------------------------------------

    @Test
    void aLicenceStillWithSmileIdJoinsTheQueueAfterTenMinutesAndCanBeDecided() {
        submitAll("d-1", "Emeka", "Okafor");
        licenceWithSmileId("d-1", 11);

        VerificationQueueItem row = verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(0, 20)).content().get(0);
        var licence = row.items().get(0);
        assertThat(licence.status()).isEqualTo(VerificationStatus.PENDING);
        assertThat(licence.awaitingProvider()).isTrue();
        assertThat(licence.submittedAt()).as("when the check started").isNotNull();
        assertThat(verification.getStatus("d-1").governmentId().awaitingProvider()).isTrue();

        verifyAll("d-1", ReviewableItem.values());
        assertThat(item(review.review("d-1", UserRole.OPS_ADMIN), ReviewableItem.GOVERNMENT_ID).awaitingProvider()).isTrue();
        var status = review.approve("d-1", null, OPS, UserRole.OPS_ADMIN);

        assertThat(status.governmentId().status()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(status.overallStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(driverProfileRepository.findByUserId("d-1").orElseThrow().isGovernmentIdDecidedDuringCheck())
                .as("Smile ID's result will override this").isTrue();
    }

    @Test
    void aLicenceJustSentToSmileIdIsntQueuedYet() {
        driverProfileService.updateMine("d-1", new UpdateDriverProfileRequest("Emeka", "Okafor", null, null, LocalDate.of(1990, 1, 1),
                null, null, "LAG12345678", null, null));
        licenceWithSmileId("d-1", 2);

        assertThat(verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(0, 20)).content()).isEmpty();
        assertThat(verification.queueCounts(UserRole.OPS_ADMIN, null).all()).isZero();
    }

    @Test
    void aLicenceStillWithSmileIdCanBeFlaggedAndRejected() {
        driverProfileService.updateMine("d-1", new UpdateDriverProfileRequest("Emeka", "Okafor", null, null, LocalDate.of(1990, 1, 1),
                null, null, "LAG12345678", null, null));
        licenceWithSmileId("d-1", 30);
        assertThat(verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(0, 20)).content())
                .extracting(VerificationQueueItem::driverId).containsExactly("d-1");

        review.mark("d-1", ReviewableItem.GOVERNMENT_ID, new ReviewMarkRequest(ReviewMark.FLAGGED, "Licence photo doesn't match", OPS), null, UserRole.OPS_ADMIN);
        var status = review.reject("d-1", null, OPS, UserRole.OPS_ADMIN);

        assertThat(status.governmentId().status()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(status.governmentId().rejectionReason()).isEqualTo("Licence photo doesn't match");
        assertThat(driverProfileRepository.findByUserId("d-1").orElseThrow().isGovernmentIdDecidedDuringCheck()).isTrue();
    }

    @Test
    void decidingALicenceAfterSmileIdAnsweredLeavesNothingToOverride() {
        submitAll("d-1", "Emeka", "Okafor");
        decide("d-1", new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.APPROVED, null));

        assertThat(driverProfileRepository.findByUserId("d-1").orElseThrow().isGovernmentIdDecidedDuringCheck()).isFalse();
    }

    @Test
    void anOverriddenApprovalStopsTheDriverPostingAndAnOverriddenRejectionLetsThemBackIn() {
        submitAll("d-1", "Emeka", "Okafor");
        decide("d-1", new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.VEHICLE, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.ROADWORTHINESS_CERT, VerificationStatus.APPROVED, null));
        org.mockito.Mockito.clearInvocations(kafkaTemplate);
        jdbcTemplate.update("update profile.driver_profiles set government_id_status = 'IN_REVIEW' where user_id = 'd-1'");

        verification.publishOverride("d-1", VerificationStatus.APPROVED, true, "SMILE_ID");
        org.mockito.Mockito.verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(org.coryd.common.kafka.KafkaTopics.DRIVER_REJECTED), any());

        jdbcTemplate.update("update profile.driver_profiles set government_id_status = 'APPROVED' where user_id = 'd-1'");
        verification.publishOverride("d-1", VerificationStatus.REJECTED, true, "SMILE_ID");
        org.mockito.Mockito.verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(org.coryd.common.kafka.KafkaTopics.DRIVER_APPROVED), any());

        org.mockito.Mockito.clearInvocations(kafkaTemplate);
        verification.publishOverride("d-1", VerificationStatus.APPROVED, true, "SMILE_ID");
        org.mockito.Mockito.verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void whatSmileIdsLateResultDidIsInTheAuditLog() {
        submitAll("d-1", "Emeka", "Okafor");
        verification.recordProviderOutcome("d-1", VerificationStatus.IN_REVIEW, "Smile ID's result came in after ops decided", "SMILE_ID");

        assertThat(review.history("d-1", UserRole.OPS_ADMIN)).singleElement().satisfies(h -> {
            assertThat(h.item()).isEqualTo(ReviewableItem.GOVERNMENT_ID);
            assertThat(h.status()).isEqualTo(VerificationStatus.IN_REVIEW);
            assertThat(h.decidedBy()).isEqualTo("SMILE_ID");
        });
    }

    private void licenceWithSmileId(String driverId, int startedMinutesAgo) {
        jdbcTemplate.update("update profile.driver_profiles set government_id_status = 'PENDING' where user_id = ?", driverId);
        smileIdCheckRepository.save(org.coryd.profile.entity.SmileIdCheck.builder().userId(driverId)
                .checkType(org.coryd.profile.entity.SmileIdCheckType.DRIVERS_LICENCE).checkId("coryd-dl-" + driverId).startedAt(java.time.Instant.now()).build());
        jdbcTemplate.update("update profile.smile_id_checks set started_at = started_at - make_interval(mins => ?) where user_id = ?", startedMinutesAgo, driverId);
        driverIndexService.refresh(driverId); // raw SQL skips the save hooks
    }

    // ---- Save Changes, and files kept once approved -------------------------------------------------

    @Test
    void saveChangesTakesBackAnApprovalTheDriverMustReuploadAndLeavesTheRestAlone() {
        submitAll("d-1", "Emeka", "Okafor");
        approveAll("d-1");
        review.mark("d-1", ReviewableItem.INSURANCE, new ReviewMarkRequest(ReviewMark.FLAGGED, "Certificate isn't genuine", OPS), null, UserRole.OPS_ADMIN);
        review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);
        assertThat(review.review("d-1", UserRole.OPS_ADMIN).canSave()).isTrue();

        var status = review.save("d-1", null, OPS, UserRole.OPS_ADMIN);

        assertThat(status.documents()).filteredOn(d -> d.type() == DocumentType.INSURANCE).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo(VerificationStatus.REJECTED);
            assertThat(d.rejectionReason()).isEqualTo("Certificate isn't genuine");
        });
        assertThat(status.vehicleRegistration().status()).as("verified again changes nothing").isEqualTo(VerificationStatus.APPROVED);
        assertThat(status.overallStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(markRepository.findByDriverId("d-1")).as("every mark cleared").isEmpty();
        assertThat(review.history("d-1", UserRole.OPS_ADMIN)).filteredOn(h -> h.status() == VerificationStatus.REJECTED)
                .singleElement().satisfies(h -> assertThat(h.item()).isEqualTo(ReviewableItem.INSURANCE));
        org.mockito.Mockito.verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(org.coryd.common.kafka.KafkaTopics.DRIVER_REJECTED), any());
    }

    @Test
    void saveChangesApprovesWhatsMarkedVerifiedWithoutNeedingEveryDocument() {
        submitAll("d-1", "Emeka", "Okafor");
        review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);

        var status = review.save("d-1", null, OPS, UserRole.OPS_ADMIN);

        assertThat(status.vehicleRegistration().status()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(status.governmentId().status()).isEqualTo(VerificationStatus.IN_REVIEW);
    }

    @Test
    void saveChangesWithNothingToChangeIsRefused() {
        submitAll("d-1", "Emeka", "Okafor");
        approveAll("d-1");
        assertThatThrownBy(() -> review.save("d-1", null, OPS, UserRole.OPS_ADMIN)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Nothing to save");
        review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);
        assertThat(review.review("d-1", UserRole.OPS_ADMIN).canSave()).as("verifying an approved document changes nothing").isFalse();
        assertThatThrownBy(() -> review.save("d-1", null, OPS, UserRole.OPS_ADMIN)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> review.save("d-1", null, OPS, UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void approvedDocumentsKeepTheirFilesSoOpsCanStillViewThem() {
        submitAll("d-1", "Emeka", "Okafor");
        approveAll("d-1");

        var status = verification.getStatus("d-1");

        assertThat(status.governmentId().url()).isEqualTo("https://img/licence-d-1.jpg");
        assertThat(status.vehicleRegistration().vehicle().exteriorPhotoUrl()).isEqualTo("https://img/ext.jpg");
        assertThat(status.vehicleRegistration().vehicle().interiorPhotoUrl()).isEqualTo("https://img/int.jpg");
        assertThat(status.documents()).allSatisfy(d -> assertThat(d.documentUrl()).isNotNull());
        org.mockito.Mockito.verify(cloudinaryService, org.mockito.Mockito.never()).delete(anyString(), any());
    }

    @Test
    void aReplacedFileIsRemovedButTheSameFileSentAgainIsNot() {
        submitAll("d-1", "Emeka", "Okafor");
        driverDocumentService.create("d-1", new CreateDriverDocumentRequest(DocumentType.INSURANCE, "https://img/new.pdf", "doc-new",
                LocalDate.now().plusYears(1), null));
        vehicleService.update(vehicleRepository.findByDriverId("d-1").get(0).getId(), "d-1",
                new VehicleRegistrationRequest("Toyota", "Corolla", 2019, "Black", "P-d-1", 4,
                        "https://img/ext2.jpg", "ext-2", "https://img/int.jpg", "int-1", true, true));
        driverProfileService.updateMine("d-1", new UpdateDriverProfileRequest("Emeka", "Okafor", null, null, LocalDate.of(1990, 1, 1),
                "https://img/licence-2.jpg", "licence-2", "LAG12345678", null, null));

        org.mockito.Mockito.verify(cloudinaryService).delete(org.mockito.ArgumentMatchers.eq("doc-INSURANCE"), any());
        org.mockito.Mockito.verify(cloudinaryService).delete(org.mockito.ArgumentMatchers.eq("ext-1"), any());
        org.mockito.Mockito.verify(cloudinaryService).delete(org.mockito.ArgumentMatchers.eq("licence-d-1"), any());
        org.mockito.Mockito.verify(cloudinaryService, org.mockito.Mockito.never()).delete(org.mockito.ArgumentMatchers.eq("int-1"), any());
    }

    private void approveAll(String driverId) {
        decide(driverId, new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.VEHICLE, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.APPROVED, null),
                new ItemDecision(ReviewableItem.ROADWORTHINESS_CERT, VerificationStatus.APPROVED, null));
    }

    // ---- only ops ----------------------------------------------------------------------------------

    @Test
    void onlyOpsCanReview() {
        submitAll("d-1", "Emeka", "Okafor");
        assertThatThrownBy(() -> review.review("d-1", UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.DRIVER))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> review.reset("d-1", ReviewableItem.VEHICLE, UserRole.RIDER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> review.approve("d-1", null, OPS, UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> review.reject("d-1", null, OPS, UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> review.history("d-1", UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private List<String> order(VerificationQueueSort sort, Sort.Direction direction) {
        return verification.queue(UserRole.OPS_ADMIN, null, null, PageRequest.of(0, 20), sort, direction).content().stream()
                .map(VerificationQueueItem::driverId).toList();
    }

    private void verifyAll(String driverId, ReviewableItem... items) {
        for (ReviewableItem item : items) {
            review.mark(driverId, item, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);
        }
    }

    private static VerificationReviewResponse.ItemReview item(VerificationReviewResponse response, ReviewableItem item) {
        return response.items().stream().filter(i -> i.item() == item).findFirst().orElseThrow();
    }

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
}
