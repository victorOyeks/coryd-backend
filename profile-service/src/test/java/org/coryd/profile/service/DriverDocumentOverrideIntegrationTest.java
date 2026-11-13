package org.coryd.profile.service;

import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.ReviewMark;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.VerificationItemDecidedEvent;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.dto.ItemApprovalRequest;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.profile.dto.ItemResubmissionRequest;
import org.coryd.profile.dto.ReviewMarkRequest;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.dto.VerificationDecisionHistoryItem;
import org.coryd.profile.dto.VerificationReviewResponse;
import org.coryd.profile.dto.VerificationStatusResponse;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.coryd.profile.repository.VerificationReviewMarkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-document ops actions on a driver: approve one document (over a Smile ID failure, with a note), ask for one again
 * (the driver sees which and why; re-uploading puts it back in review), and the rule that a licence Smile ID approved
 * stays approved - unless it was a partial name match ops hasn't reviewed. Against a throwaway Postgres container.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({VerificationAggregationService.class, VerificationReviewService.class, DriverProfileService.class, VehicleService.class,
        DriverDocumentService.class, EmergencyContactService.class, RiderProfileService.class, ReplacedFiles.class,
        DriverIndexService.class, DriverIndexer.class})
class DriverDocumentOverrideIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OPS = "Ada Ops";
    private static final String OPS_ID = "0b5e0000-0000-0000-0000-000000000001";

    @Autowired VerificationAggregationService verification;
    @Autowired VerificationReviewService review;
    @Autowired DriverProfileService driverProfileService;
    @Autowired VehicleService vehicleService;
    @Autowired DriverDocumentService driverDocumentService;
    @Autowired DriverProfileRepository driverProfileRepository;
    @Autowired DriverIndexRepository driverIndexRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DriverDocumentRepository driverDocumentRepository;
    @Autowired EmergencyContactRepository emergencyContactRepository;
    @Autowired VerificationReviewMarkRepository markRepository;
    @Autowired VerificationDecisionLogRepository decisionLogRepository;
    @Autowired VerificationCheckRepository verificationCheckRepository;
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

    // ---- request resubmission -----------------------------------------------------------------------------------

    @Test
    void askingForADocumentAgainShowsTheDriverWhichAndWhyUntilTheyReupload() {
        submitAll("d-1");
        approveAll("d-1");

        VerificationStatusResponse status = review.requestResubmission("d-1", ReviewableItem.INSURANCE,
                new ItemResubmissionRequest("  The policy number is cut off - upload the full page  ", null), OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(insurance(status).status()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(insurance(status).rejectionReason()).isEqualTo("The policy number is cut off - upload the full page");
        assertThat(status.overallStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(status.resubmissionRequests()).singleElement().satisfies(r -> {
            assertThat(r.item()).isEqualTo(ReviewableItem.INSURANCE);
            assertThat(r.label()).isEqualTo("Insurance certificate");
            assertThat(r.reason()).isEqualTo("The policy number is cut off - upload the full page");
            assertThat(r.requestedAt()).isNotNull();
        });
        assertThat(verification.getStatusFor("d-1", "d-1", UserRole.DRIVER).resubmissionRequests())
                .as("the driver app's own status call").hasSize(1);
        assertThat(review.history("d-1", UserRole.OPS_ADMIN).get(0)).satisfies(h -> {
            assertThat(h.action()).isEqualTo(VerificationDecisionAction.RESUBMISSION_REQUESTED);
            assertThat(h.subject()).isEqualTo(DecisionSubject.INSURANCE);
            assertThat(h.item()).isEqualTo(ReviewableItem.INSURANCE);
            assertThat(h.previousStatus()).isEqualTo(VerificationStatus.APPROVED);
            assertThat(h.status()).isEqualTo(VerificationStatus.REJECTED);
            assertThat(h.decidedBy()).isEqualTo(OPS);
            assertThat(h.decidedById()).isEqualTo(OPS_ID);
        });
        VerificationItemDecidedEvent pushed = lastItemEvent();
        assertThat(pushed.isApproved()).isFalse();
        assertThat(pushed.getAction()).isEqualTo("RESUBMISSION_REQUESTED");
        assertThat(pushed.getRejectionReason()).isEqualTo("The policy number is cut off - upload the full page");

        driverDocumentService.create("d-1", new CreateDriverDocumentRequest(DocumentType.INSURANCE, "https://img/ins-2.pdf", "ins-2",
                LocalDate.now().plusYears(1), null));

        VerificationStatusResponse after = verification.getStatus("d-1");
        assertThat(insurance(after).status()).as("back in review").isEqualTo(VerificationStatus.IN_REVIEW);
        assertThat(insurance(after).resubmitted()).isTrue();
        assertThat(after.resubmissionRequests()).isEmpty();
        assertThat(verification.queue(UserRole.OPS_ADMIN, VerificationQueueTab.RESUBMITTED, null, PageRequest.of(0, 20)).content())
                .singleElement().satisfies(r -> assertThat(r.driverId()).isEqualTo("d-1"));
    }

    @Test
    void aResubmissionRequestNeedsAReasonAndSomethingToSendBack() {
        submitAll("d-1");

        assertThatThrownBy(() -> review.requestResubmission("d-1", ReviewableItem.VEHICLE, new ItemResubmissionRequest(" ", null), OPS, OPS_ID,
                UserRole.OPS_ADMIN)).isInstanceOf(BadRequestException.class)
                .hasMessage("Give the driver a reason so they know what to fix in the vehicle registration");
        assertThatThrownBy(() -> review.requestResubmission("d-1", ReviewableItem.VEHICLE, null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> review.requestResubmission("d-1", ReviewableItem.VEHICLE, new ItemResubmissionRequest("x".repeat(256), null),
                OPS, OPS_ID, UserRole.OPS_ADMIN)).hasMessageContaining("255 characters");

        review.requestResubmission("d-1", ReviewableItem.VEHICLE, new ItemResubmissionRequest("Plate not readable", null), OPS, OPS_ID,
                UserRole.OPS_ADMIN);
        assertThatThrownBy(() -> review.requestResubmission("d-1", ReviewableItem.VEHICLE, new ItemResubmissionRequest("Again", null), OPS,
                OPS_ID, UserRole.OPS_ADMIN)).hasMessage("The vehicle registration is already waiting for the driver to upload it again");

        driverProfileService.updateMine("d-2", new UpdateDriverProfileRequest("Tola", "Ade", null, null, LocalDate.of(1990, 1, 1),
                null, null, "LAG12345678", null, null));
        assertThatThrownBy(() -> review.requestResubmission("d-2", ReviewableItem.INSURANCE, new ItemResubmissionRequest("Blurry", null), OPS,
                OPS_ID, UserRole.OPS_ADMIN)).hasMessage("The insurance certificate hasn't been uploaded yet - there's nothing to send back");
        assertThatThrownBy(() -> review.requestResubmission("d-1", ReviewableItem.INSURANCE, new ItemResubmissionRequest("Blurry", null), OPS,
                OPS_ID, UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
    }

    // ---- a licence Smile ID approved stays approved -------------------------------------------------------------

    @Test
    void aLicenceSmileIdApprovedCantBeFlaggedRejectedOrSentBack() {
        submitAll("d-1");
        smileIdApprovedLicence("d-1", VerificationCheckOutcome.MATCH);
        String locked = "Smile ID verified this driver's licence, so it stays approved - only a licence approved on a partial name "
                + "match that ops hasn't reviewed yet can be rejected or sent back";

        assertThatThrownBy(() -> review.requestResubmission("d-1", ReviewableItem.GOVERNMENT_ID, new ItemResubmissionRequest("Blurry", null),
                OPS, OPS_ID, UserRole.OPS_ADMIN)).isInstanceOf(BadRequestException.class).hasMessage(locked);
        assertThatThrownBy(() -> review.mark("d-1", ReviewableItem.GOVERNMENT_ID, new ReviewMarkRequest(ReviewMark.FLAGGED, "Looks off", OPS),
                null, UserRole.OPS_ADMIN)).hasMessage(locked);
        assertThatThrownBy(() -> verification.decide("d-1", UserRole.OPS_ADMIN, new VerificationDecisionBatchRequest(
                List.of(new ItemDecision(ReviewableItem.GOVERNMENT_ID, VerificationStatus.REJECTED, "Looks off")), OPS))).hasMessage(locked);

        VerificationReviewResponse.ItemReview licence = item(review.review("d-1", UserRole.OPS_ADMIN), ReviewableItem.GOVERNMENT_ID);
        assertThat(licence.lockedBySmileId()).isTrue();
        assertThat(licence.canRequestResubmission()).isFalse();
        assertThat(licence.resubmissionBlockedReason()).isEqualTo(locked);
        assertThat(driverProfileRepository.findByUserId("d-1").orElseThrow().getGovernmentIdStatus()).isEqualTo(VerificationStatus.APPROVED);
        // verifying it is harmless and still allowed
        review.mark("d-1", ReviewableItem.GOVERNMENT_ID, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);
    }

    @Test
    void aLicenceApprovedOnAPartialNameMatchCanStillBeSentBack() {
        submitAll("d-1");
        smileIdApprovedLicence("d-1", VerificationCheckOutcome.PARTIAL_NAME_MATCH);

        assertThat(item(review.review("d-1", UserRole.OPS_ADMIN), ReviewableItem.GOVERNMENT_ID).lockedBySmileId()).isFalse();
        VerificationStatusResponse status = review.requestResubmission("d-1", ReviewableItem.GOVERNMENT_ID,
                new ItemResubmissionRequest("The licence is in someone else's name", null), OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(status.governmentId().status()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(status.resubmissionRequests()).extracting(VerificationStatusResponse.ResubmissionRequest::item)
                .containsExactly(ReviewableItem.GOVERNMENT_ID);
    }

    @Test
    void aLicenceOpsApprovedCanStillBeSentBack() {
        submitAll("d-1");
        approveAll("d-1");

        assertThat(review.requestResubmission("d-1", ReviewableItem.GOVERNMENT_ID, new ItemResubmissionRequest("Expired licence", null), OPS,
                OPS_ID, UserRole.OPS_ADMIN).governmentId().status()).isEqualTo(VerificationStatus.REJECTED);
    }

    // ---- approve one document -----------------------------------------------------------------------------------

    @Test
    void aLicenceSmileIdFailedCanBeApprovedByOpsWithANoteRecordedAsAnOverride() {
        submitAll("d-1");
        smileIdFailedLicence("d-1");

        VerificationReviewResponse.ItemReview licence = item(review.review("d-1", UserRole.OPS_ADMIN), ReviewableItem.GOVERNMENT_ID);
        assertThat(licence.status()).isEqualTo(VerificationStatus.PENDING);
        assertThat(licence.canApproveItem()).isTrue();
        assertThat(licence.approveItemNeedsNote()).isTrue();
        assertThat(licence.providerOutcome()).isEqualTo("FACE_MISMATCH");

        assertThatThrownBy(() -> review.approveItem("d-1", ReviewableItem.GOVERNMENT_ID, null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Add a note saying why you're approving the driver's licence - it goes against Smile ID's result");

        VerificationStatusResponse status = review.approveItem("d-1", ReviewableItem.GOVERNMENT_ID,
                new ItemApprovalRequest("Selfie was in poor light - matched the licence in person", null), OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(status.governmentId().status()).isEqualTo(VerificationStatus.APPROVED);
        VerificationDecisionHistoryItem logged = review.history("d-1", UserRole.OPS_ADMIN).get(0);
        assertThat(logged.action()).isEqualTo(VerificationDecisionAction.OVERRIDE_APPROVED);
        assertThat(logged.overrodeProvider()).isTrue();
        assertThat(logged.providerOutcome()).isEqualTo("FACE_MISMATCH");
        assertThat(logged.previousStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(logged.note()).isEqualTo("Selfie was in poor light - matched the licence in person");
        assertThat(logged.decidedBy()).isEqualTo(OPS);
        assertThat(logged.decidedById()).isEqualTo(OPS_ID);
        assertThat(lastItemEvent().isApproved()).as("the driver is told, as for any approval").isTrue();
        assertThat(driverProfileRepository.findByUserId("d-1").orElseThrow().isGovernmentIdDecidedDuringCheck())
                .as("Smile ID had answered - nothing left to override this").isFalse();
    }

    @Test
    void aLicenceSmileIdSentToOpsIsApprovedWithoutANoteButStillLoggedAsAnOverride() {
        submitAll("d-1");
        verificationCheckRepository.save(VerificationCheck.builder().driverId("d-1").checkType(VerificationCheckType.DRIVERS_LICENCE)
                .subjectKey("coryd-dl-1").outcome(VerificationCheckOutcome.NAME_MISMATCH).providerSummary("Name differs").build());
        verifyAll("d-1");

        review.approve("d-1", null, OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(review.history("d-1", UserRole.OPS_ADMIN)).filteredOn(h -> h.item() == ReviewableItem.GOVERNMENT_ID).singleElement()
                .satisfies(h -> {
                    assertThat(h.action()).isEqualTo(VerificationDecisionAction.OVERRIDE_APPROVED);
                    assertThat(h.providerOutcome()).isEqualTo("NAME_MISMATCH");
                    assertThat(h.decidedById()).isEqualTo(OPS_ID);
                });
        assertThat(review.history("d-1", UserRole.OPS_ADMIN)).filteredOn(h -> h.item() == ReviewableItem.VEHICLE).singleElement()
                .satisfies(h -> assertThat(h.action()).isEqualTo(VerificationDecisionAction.APPROVED));
    }

    @Test
    void oneDocumentCanBeApprovedOnItsOwnAndItsMarkIsCleared() {
        submitAll("d-1");
        review.mark("d-1", ReviewableItem.VEHICLE, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);

        VerificationStatusResponse status = review.approveItem("d-1", ReviewableItem.VEHICLE, null, OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(status.vehicleRegistration().status()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(status.overallStatus()).as("the rest still waits").isEqualTo(VerificationStatus.IN_REVIEW);
        assertThat(markRepository.findByDriverId("d-1")).isEmpty();
        assertThat(review.history("d-1", UserRole.OPS_ADMIN).get(0).action()).isEqualTo(VerificationDecisionAction.APPROVED);
        assertThatThrownBy(() -> review.approveItem("d-1", ReviewableItem.VEHICLE, null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .hasMessage("The vehicle registration is already approved");
        assertThatThrownBy(() -> review.approveItem("d-1", ReviewableItem.VEHICLE, null, OPS, OPS_ID, UserRole.RIDER))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aRejectedDocumentCanBeApprovedOnlyWithANoteAndANeverUploadedOneNotAtAll() {
        submitAll("d-1");
        review.requestResubmission("d-1", ReviewableItem.INSURANCE, new ItemResubmissionRequest("Blurry", null), OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThatThrownBy(() -> review.approveItem("d-1", ReviewableItem.INSURANCE, null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .hasMessage("Add a note saying why you're approving the insurance certificate - it goes against its rejection");
        assertThat(insurance(review.approveItem("d-1", ReviewableItem.INSURANCE, new ItemApprovalRequest("Checked with the insurer", null),
                OPS, OPS_ID, UserRole.OPS_ADMIN)).status()).isEqualTo(VerificationStatus.APPROVED);

        driverProfileService.updateMine("d-2", new UpdateDriverProfileRequest("Tola", "Ade", null, null, LocalDate.of(1990, 1, 1),
                null, null, "LAG12345678", null, null));
        assertThatThrownBy(() -> review.approveItem("d-2", ReviewableItem.GOVERNMENT_ID, null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .hasMessage("The driver's licence hasn't been uploaded yet");
    }

    // ---- unit: the per-document rules ---------------------------------------------------------------------------

    @Test
    void perDocumentRules() {
        var pendingLicence = new org.coryd.profile.dto.VerificationQueueItem.Entry(ReviewableItem.GOVERNMENT_ID, VerificationStatus.PENDING, null, false);
        assertThat(VerificationReviewService.approveItemBlockedReason(pendingLicence, null)).isNotNull();
        assertThat(VerificationReviewService.approveItemBlockedReason(pendingLicence, "NOT_A_LICENCE")).isNull();
        assertThat(VerificationReviewService.approveItemNeedsNote(pendingLicence, "NOT_A_LICENCE")).isTrue();
        var awaiting = new org.coryd.profile.dto.VerificationQueueItem.Entry(ReviewableItem.GOVERNMENT_ID, VerificationStatus.PENDING, null, false, true);
        assertThat(VerificationReviewService.approveItemBlockedReason(awaiting, null)).isNull();
        assertThat(VerificationReviewService.approveItemNeedsNote(awaiting, null)).isFalse();
        assertThat(VerificationReviewService.resubmissionBlockedReason(awaiting, null)).isNull();
        var expired = new org.coryd.profile.dto.VerificationQueueItem.Entry(ReviewableItem.INSURANCE, VerificationStatus.EXPIRED, null, false);
        assertThat(VerificationReviewService.approveItemBlockedReason(expired, null)).contains("expired");
        assertThat(VerificationReviewService.resubmissionBlockedReason(expired, null)).isNull();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The licence approved by Smile ID itself, as LicenceCheck does it. */
    private void smileIdApprovedLicence(String driverId, VerificationCheckOutcome outcome) {
        jdbcTemplate.update("update profile.driver_profiles set government_id_status = 'PENDING' where user_id = ?", driverId);
        verificationCheckRepository.save(VerificationCheck.builder().driverId(driverId).checkType(VerificationCheckType.DRIVERS_LICENCE)
                .subjectKey("coryd-dl-" + driverId).outcome(outcome).providerSummary("Genuine licence").build());
        verification.autoApproveGovernmentId(driverId, LicenceCheck.DECIDED_BY);
    }

    /** Smile ID asked the driver to try again (face mismatch) - the licence waits for a new check. */
    private void smileIdFailedLicence(String driverId) {
        jdbcTemplate.update("update profile.driver_profiles set government_id_status = 'PENDING' where user_id = ?", driverId);
        smileIdCheckRepository.save(SmileIdCheck.builder().userId(driverId).checkType(SmileIdCheckType.DRIVERS_LICENCE)
                .checkId("coryd-dl-" + driverId).attempts(1).retryReason(SmileIdRetryReason.FACE_MISMATCH).build());
        verificationCheckRepository.save(VerificationCheck.builder().driverId(driverId).checkType(VerificationCheckType.DRIVERS_LICENCE)
                .subjectKey("coryd-dl-" + driverId).outcome(VerificationCheckOutcome.DETAILS_MISMATCH)
                .providerSummary("Selfie does not match the photo on the licence").build());
    }

    private void submitAll(String driverId) {
        driverProfileService.updateMine(driverId, new UpdateDriverProfileRequest("Emeka", "Okafor", null, null, LocalDate.of(1990, 1, 1),
                "https://img/licence-" + driverId + ".jpg", "licence-" + driverId, "LAG12345678", null, null));
        vehicleService.register(driverId, driverId, new VehicleRegistrationRequest("Toyota", "Corolla", 2019, "Black", "P-" + driverId, 4,
                "https://img/ext.jpg", "ext-1", "https://img/int.jpg", "int-1", true, true));
        for (DocumentType type : List.of(DocumentType.INSURANCE, DocumentType.ROADWORTHINESS_CERT)) {
            driverDocumentService.create(driverId, new CreateDriverDocumentRequest(type, "https://img/" + type + ".jpg", "doc-" + type,
                    LocalDate.now().plusYears(1), null));
        }
    }

    private void approveAll(String driverId) {
        verifyAll(driverId);
        review.approve(driverId, null, OPS, OPS_ID, UserRole.OPS_ADMIN);
    }

    private void verifyAll(String driverId) {
        for (ReviewableItem item : ReviewableItem.values()) {
            review.mark(driverId, item, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);
        }
    }

    private static VerificationStatusResponse.DocumentStatus insurance(VerificationStatusResponse status) {
        return status.documents().stream().filter(d -> d.type() == DocumentType.INSURANCE).findFirst().orElseThrow();
    }

    private static VerificationReviewResponse.ItemReview item(VerificationReviewResponse response, ReviewableItem item) {
        return response.items().stream().filter(i -> i.item() == item).findFirst().orElseThrow();
    }

    private VerificationItemDecidedEvent lastItemEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaTopics.VERIFICATION_ITEM_DECIDED), captor.capture());
        List<Object> all = captor.getAllValues();
        return (VerificationItemDecidedEvent) all.get(all.size() - 1);
    }
}
