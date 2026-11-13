package org.coryd.profile.service;

import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.IdentityReviewRole;
import org.coryd.common.enums.IdentityReviewSort;
import org.coryd.common.enums.IdentityReviewTab;
import org.coryd.common.enums.RiderDirectoryStatus;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.IdentityVerificationChangedEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.IdentityActionRequest;
import org.coryd.profile.dto.IdentityReviewCounts;
import org.coryd.profile.dto.IdentityReviewDetail;
import org.coryd.profile.dto.IdentityReviewRow;
import org.coryd.profile.dto.VerificationDecisionHistoryItem;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.RiderIndex;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.RiderIndexRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ops identity (NIN) back office against a throwaway Postgres: tabs, counts, search, sort and paging in SQL; one
 * person's checks, tries and actions; approve (and override Smile ID), reject with a reason, reset tries - with the rules
 * that keep a Smile ID approval approved, the audit trail and the event that tells the person.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({IdentityReviewService.class, IdentityVerificationDecider.class, SmileIdProperties.class})
class IdentityReviewIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OPS = "Ada Ops";
    private static final String OPS_ID = "0b5e0000-0000-0000-0000-000000000001";

    @Autowired IdentityReviewService service;
    @Autowired SmileIdProperties properties;
    @Autowired IdentityVerificationRepository identityVerificationRepository;
    @Autowired RiderProfileRepository riderProfileRepository;
    @Autowired DriverProfileRepository driverProfileRepository;
    @Autowired RiderIndexRepository riderIndexRepository;
    @Autowired DriverIndexRepository driverIndexRepository;
    @Autowired SmileIdCheckRepository smileIdCheckRepository;
    @Autowired VerificationCheckRepository verificationCheckRepository;
    @Autowired VerificationDecisionLogRepository decisionLogRepository;

    @MockBean VerifiedIdentityService verifiedIdentityService;
    @MockBean DriverIdentity driverIdentity;
    @MockBean LicenceReviewService licenceReviewService;
    @MockBean IdentityServiceClient identityServiceClient;
    @MockBean CloudinaryService cloudinaryService;
    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void cleanSlate() {
        decisionLogRepository.deleteAll();
        verificationCheckRepository.deleteAll();
        smileIdCheckRepository.deleteAll();
        identityVerificationRepository.deleteAll();
        riderIndexRepository.deleteAll();
        driverIndexRepository.deleteAll();
        riderProfileRepository.deleteAll();
        driverProfileRepository.deleteAll();
        properties.setSandbox(false);
        when(kafkaTemplate.send(anyString(), any())).thenReturn(new CompletableFuture<>());
    }

    // ---- the list -----------------------------------------------------------------------------------------------

    @Test
    void everyoneIsInExactlyOneTabWithCountsToMatch() {
        everyTab();

        IdentityReviewCounts counts = service.counts(UserRole.OPS_ADMIN, null, null);
        assertThat(counts).isEqualTo(new IdentityReviewCounts(7, 1, 1, 2, 1, 2));

        assertThat(ids(IdentityReviewTab.PENDING)).containsExactly("r-pending");
        assertThat(ids(IdentityReviewTab.IN_REVIEW)).containsExactly("r-review");
        assertThat(ids(IdentityReviewTab.FAILED)).containsExactlyInAnyOrder("r-failed", "r-rejected");
        assertThat(ids(IdentityReviewTab.APPROVED)).containsExactly("r-approved");
        assertThat(ids(IdentityReviewTab.LOCKED_OUT)).as("5 NIN tries, or 3 NIN lookups for a driver")
                .containsExactlyInAnyOrder("r-locked", "d-lookup");
    }

    @Test
    void aRowCarriesTheTriesTheRetryReasonAndSmileIdsLastWord() {
        everyTab();

        IdentityReviewRow failed = row("r-failed");
        assertThat(failed.tab()).isEqualTo(IdentityReviewTab.FAILED);
        assertThat(failed.status()).isEqualTo(VerificationStatus.PENDING);
        assertThat(failed.attemptsUsed()).isEqualTo(1);
        assertThat(failed.attemptsLeft()).isEqualTo(4);
        assertThat(failed.retryReason()).isEqualTo(SmileIdRetryReason.FACE_MISMATCH);
        assertThat(failed.latestOutcome()).isEqualTo(VerificationCheckOutcome.DETAILS_MISMATCH);
        assertThat(failed.firstName()).isEqualTo("Bola");
        assertThat(failed.phone()).isEqualTo("08031110002");
        assertThat(failed.rider()).isTrue();
        assertThat(failed.driver()).isFalse();

        IdentityReviewRow lookup = row("d-lookup");
        assertThat(lookup.driver()).isTrue();
        assertThat(lookup.attemptsLeft()).isZero();
        assertThat(row("r-pending").checkInProgress()).isTrue();
    }

    @Test
    void searchesByNameOrPhoneAndNarrowsByRole() {
        everyTab();

        assertThat(service.list(UserRole.OPS_ADMIN, null, "BOLA", null, null, null, 0, 20).content())
                .extracting(IdentityReviewRow::userId).containsExactly("r-failed");
        assertThat(service.list(UserRole.OPS_ADMIN, null, "0803 111 0002", null, null, null, 0, 20).content())
                .as("spaces in a typed phone don't matter").extracting(IdentityReviewRow::userId).containsExactly("r-failed");
        assertThat(service.list(UserRole.OPS_ADMIN, null, "+2348031110002", null, null, null, 0, 20).content())
                .as("+234 and 0 are the same phone").extracting(IdentityReviewRow::userId).containsExactly("r-failed");
        assertThat(service.list(UserRole.OPS_ADMIN, null, null, IdentityReviewRole.DRIVER, null, null, 0, 20).content())
                .extracting(IdentityReviewRow::userId).containsExactly("d-lookup");
        assertThat(service.counts(UserRole.OPS_ADMIN, "bola", null).all()).isEqualTo(1);
        assertThat(service.counts(UserRole.OPS_ADMIN, null, IdentityReviewRole.RIDER).all()).isEqualTo(6);
        assertThat(service.list(UserRole.OPS_ADMIN, null, "100%_", null, null, null, 0, 20).content())
                .as("wildcards are matched literally").isEmpty();
    }

    @Test
    void sortsAndPagesInTheDatabase() {
        everyTab();

        PagedResponse<IdentityReviewRow> first = service.list(UserRole.OPS_ADMIN, null, null, null, IdentityReviewSort.NAME,
                Sort.Direction.ASC, 0, 3);
        PagedResponse<IdentityReviewRow> last = service.list(UserRole.OPS_ADMIN, null, null, null, IdentityReviewSort.NAME,
                Sort.Direction.ASC, 2, 3);
        assertThat(first.content()).extracting(IdentityReviewRow::firstName).containsExactly("Ada", "Bola", "Chidi");
        assertThat(first.totalElements()).isEqualTo(7);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.last()).isFalse();
        assertThat(last.content()).hasSize(1);
        assertThat(last.last()).isTrue();

        assertThat(service.list(UserRole.OPS_ADMIN, null, null, null, IdentityReviewSort.ATTEMPTS, Sort.Direction.DESC, 0, 2)
                .content()).extracting(IdentityReviewRow::userId).containsExactly("r-locked", "d-lookup");
        assertThat(service.list(UserRole.OPS_ADMIN, null, null, null, null, null, 0, 1).content())
                .as("oldest submission first by default").extracting(IdentityReviewRow::userId).containsExactly("r-approved");
    }

    // ---- one person ---------------------------------------------------------------------------------------------

    @Test
    void detailShowsEveryCheckTheTriesAndWhatOpsCanDo() {
        everyTab();

        IdentityReviewDetail failed = service.detail("r-failed", UserRole.OPS_ADMIN);
        assertThat(failed.tab()).isEqualTo(IdentityReviewTab.FAILED);
        assertThat(failed.person().phone()).isEqualTo("08031110002");
        assertThat(failed.attempts().checkType()).isEqualTo(SmileIdCheckType.NIN);
        assertThat(failed.attempts().used()).isEqualTo(1);
        assertThat(failed.attempts().left()).isEqualTo(4);
        assertThat(failed.attempts().retryReason()).isEqualTo(SmileIdRetryReason.FACE_MISMATCH);
        assertThat(failed.checks()).singleElement().satisfies(c -> {
            assertThat(c.outcome()).isEqualTo(VerificationCheckOutcome.DETAILS_MISMATCH);
            assertThat(c.smileJobId()).isEqualTo("job-r-failed");
            assertThat(c.hasImages()).isTrue();
        });
        assertThat(failed.actions().canApprove()).isTrue();
        assertThat(failed.actions().approveNeedsNote()).as("approving overrides Smile ID").isTrue();
        assertThat(failed.actions().canReject()).isTrue();
        assertThat(failed.actions().canResetAttempts()).isTrue();

        IdentityReviewDetail approved = service.detail("r-approved", UserRole.OPS_ADMIN);
        assertThat(approved.actions().canApprove()).isFalse();
        assertThat(approved.actions().canReject()).isFalse();
        assertThat(approved.actions().rejectBlockedReason()).startsWith("Smile ID verified this identity");
        assertThat(approved.actions().canResetAttempts()).isFalse();

        assertThat(service.detail("r-review", UserRole.OPS_ADMIN).actions().approveNeedsNote()).isFalse();
        assertThat(service.detail("d-lookup", UserRole.OPS_ADMIN).driverNinStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThatThrownBy(() -> service.detail("nobody", UserRole.OPS_ADMIN)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- approve ------------------------------------------------------------------------------------------------

    @Test
    void approvingOverASmileIdFailureNeedsANoteAndIsRecordedAsAnOverride() {
        everyTab();

        assertThatThrownBy(() -> service.approve("r-failed", new IdentityActionRequest(" ", null, null), OPS, OPS_ID, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Add a note saying why you're approving - it overrides Smile ID's result");

        IdentityReviewDetail detail = service.approve("r-failed",
                new IdentityActionRequest("Selfie matched the NIN photo on a video call", null, null), OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(detail.status()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(detail.tab()).isEqualTo(IdentityReviewTab.APPROVED);
        assertThat(detail.providerOverridden()).isTrue();
        assertThat(detail.decisionNote()).isEqualTo("Selfie matched the NIN photo on a video call");
        assertThat(detail.decidedBy()).isEqualTo(OPS_ID);
        assertThat(detail.decidedAt()).isNotNull();
        assertThat(detail.history()).singleElement().satisfies(h -> {
            assertThat(h.subject()).isEqualTo(DecisionSubject.IDENTITY);
            assertThat(h.item()).isNull();
            assertThat(h.label()).isEqualTo("Identity (NIN)");
            assertThat(h.action()).isEqualTo(VerificationDecisionAction.OVERRIDE_APPROVED);
            assertThat(h.previousStatus()).isEqualTo(VerificationStatus.PENDING);
            assertThat(h.status()).isEqualTo(VerificationStatus.APPROVED);
            assertThat(h.overrodeProvider()).isTrue();
            assertThat(h.providerOutcome()).isEqualTo("FACE_MISMATCH");
            assertThat(h.note()).isEqualTo("Selfie matched the NIN photo on a video call");
            assertThat(h.decidedBy()).isEqualTo(OPS);
            assertThat(h.decidedById()).isEqualTo(OPS_ID);
            assertThat(h.decidedAt()).isNotNull();
        });
        IdentityVerificationChangedEvent event = lastEvent();
        assertThat(event.getUserId()).isEqualTo("r-failed");
        assertThat(event.isVerified()).isTrue();
        assertThat(event.getAction()).isEqualTo("OVERRIDE_APPROVED");
        verify(verifiedIdentityService).applyFromLatestCheck("r-failed");

        assertThatThrownBy(() -> service.approve("r-failed", null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class).hasMessage("This person's identity is already verified");
    }

    @Test
    void lockedOutAndRejectedChecksCanBeApprovedWithANote() {
        everyTab();

        assertThatThrownBy(() -> service.approve("r-locked", null, OPS, OPS_ID, UserRole.OPS_ADMIN)).isInstanceOf(BadRequestException.class);
        assertThat(service.approve("r-locked", new IdentityActionRequest("Checked in person at the hub", null, null), OPS, OPS_ID,
                UserRole.OPS_ADMIN).status()).isEqualTo(VerificationStatus.APPROVED);

        assertThatThrownBy(() -> service.approve("r-rejected", null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .hasMessage("Add a note saying why you're approving - it reverses the earlier rejection");
        assertThat(service.approve("r-rejected", new IdentityActionRequest("Sent the right NIN slip", null, null), OPS, OPS_ID,
                UserRole.OPS_ADMIN).status()).isEqualTo(VerificationStatus.APPROVED);
    }

    @Test
    void anInReviewCheckIsApprovedWithoutANoteAndStillRecordsWhatSmileIdFound() {
        everyTab();

        IdentityReviewDetail detail = service.approve("r-review", null, OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(detail.status()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(detail.history()).singleElement().satisfies(h -> {
            assertThat(h.action()).isEqualTo(VerificationDecisionAction.OVERRIDE_APPROVED);
            assertThat(h.providerOutcome()).isEqualTo("NAME_MISMATCH");
            assertThat(h.previousStatus()).isEqualTo(VerificationStatus.IN_REVIEW);
        });
    }

    @Test
    void aDriversFailedNinLookupIsAcceptedAndTheyAreVerifiedOnceTheLicenceIsToo() {
        everyTab();

        assertThatThrownBy(() -> service.approve("d-lookup", null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .as("overriding NIMC's 'not found' needs a note").isInstanceOf(BadRequestException.class);
        IdentityReviewDetail waiting = service.approve("d-lookup", new IdentityActionRequest("NIN slip checked", null, null), OPS, OPS_ID,
                UserRole.OPS_ADMIN);
        assertThat(waiting.driverNinStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(waiting.status()).as("the licence is still outstanding").isEqualTo(VerificationStatus.PENDING);
        assertThat(waiting.tab()).as("no longer locked out - waiting for the licence").isEqualTo(IdentityReviewTab.PENDING);
        assertThat(waiting.actions().canApprove()).isFalse();
        assertThat(waiting.history().get(0).providerOutcome()).isEqualTo("NIN_NOT_FOUND");
        assertThat(lastEvent().getAction()).isEqualTo("OVERRIDE_APPROVED");
        assertThat(lastEvent().isVerified()).isFalse();
        assertThat(ids(IdentityReviewTab.LOCKED_OUT)).containsExactly("r-locked");

        // a second driver whose licence is already approved is verified straight away
        identity("d-lookup2", VerificationStatus.IN_REVIEW, null, Instant.now());
        driverProfileRepository.save(DriverProfile.builder().userId("d-lookup2").firstName("Femi").lastName("Ade")
                .governmentIdStatus(VerificationStatus.APPROVED).ninStatus(VerificationStatus.IN_REVIEW).build());
        smileIdCheckRepository.save(SmileIdCheck.builder().userId("d-lookup2").checkType(SmileIdCheckType.NIN_LOOKUP).attempts(1).build());
        assertThat(service.approve("d-lookup2", null, OPS, OPS_ID, UserRole.OPS_ADMIN).status()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(driverProfileRepository.findByUserId("d-lookup2").orElseThrow().getNinStatus()).isEqualTo(VerificationStatus.APPROVED);
    }

    // ---- reject -------------------------------------------------------------------------------------------------

    @Test
    void aRejectionNeedsAReasonAndTellsThePerson() {
        everyTab();

        assertThatThrownBy(() -> service.reject("r-review", new IdentityActionRequest(null, "  ", null), OPS, OPS_ID, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Give a reason for rejecting - the person sees it and needs it to put things right");
        assertThatThrownBy(() -> service.reject("r-review", null, OPS, OPS_ID, UserRole.OPS_ADMIN)).isInstanceOf(BadRequestException.class);

        IdentityReviewDetail detail = service.reject("r-review", new IdentityActionRequest(null, "The NIN is someone else's", null),
                OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(detail.status()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(detail.rejectionReason()).isEqualTo("The NIN is someone else's");
        assertThat(detail.tab()).isEqualTo(IdentityReviewTab.FAILED);
        assertThat(detail.history()).singleElement().satisfies(h -> {
            assertThat(h.action()).isEqualTo(VerificationDecisionAction.REJECTED);
            assertThat(h.previousStatus()).isEqualTo(VerificationStatus.IN_REVIEW);
            assertThat(h.note()).isEqualTo("The NIN is someone else's");
        });
        IdentityVerificationChangedEvent event = lastEvent();
        assertThat(event.getAction()).isEqualTo("REJECTED");
        assertThat(event.getReason()).isEqualTo("The NIN is someone else's");
        assertThat(event.isVerified()).isFalse();

        assertThatThrownBy(() -> service.reject("r-review", new IdentityActionRequest(null, "again", null), OPS, OPS_ID, UserRole.OPS_ADMIN))
                .hasMessage("This identity check is already rejected");
    }

    @Test
    void whatSmileIdApprovedStaysApproved() {
        everyTab();
        clearInvocations(kafkaTemplate);

        assertThatThrownBy(() -> service.reject("r-approved", new IdentityActionRequest(null, "Looks wrong", null), OPS, OPS_ID,
                UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Smile ID verified this identity, so it stays approved - only an approval on a partial name match that "
                        + "hasn't been reviewed yet can be rejected");
        assertThat(identityVerificationRepository.findByUserId("r-approved").orElseThrow().getStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(decisionLogRepository.findAll()).isEmpty();
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    void anApprovalOnAPartialNameMatchCanStillBeRejectedAndLeavesTheReviewList() {
        identity("r-partial", VerificationStatus.APPROVED, IdentityVerificationDecider.SMILE_ID, Instant.now());
        rider("r-partial", "Grace", "Oyekanmi", null);
        VerificationCheck partial = verificationCheckRepository.save(VerificationCheck.builder().driverId("r-partial")
                .checkType(VerificationCheckType.NIN_FACE).subjectKey("coryd-nin-partial").outcome(VerificationCheckOutcome.PARTIAL_NAME_MATCH)
                .providerSummary("Approved on a partial name match - review: NIN is held by Grace Dare").build());

        IdentityReviewDetail detail = service.reject("r-partial", new IdentityActionRequest(null, "Not the same person", null), OPS, OPS_ID,
                UserRole.OPS_ADMIN);

        assertThat(detail.status()).isEqualTo(VerificationStatus.REJECTED);
        VerificationCheck reviewed = verificationCheckRepository.findById(partial.getId()).orElseThrow();
        assertThat(reviewed.getReviewedAt()).as("off the partial-name-match list").isNotNull();
        assertThat(reviewed.getReviewedBy()).isEqualTo(OPS_ID);
        assertThat(detail.history().get(0).previousStatus()).isEqualTo(VerificationStatus.APPROVED);
    }

    @Test
    void aPartialMatchOpsAlreadyReviewedCanNoLongerBeRejected() {
        identity("r-partial", VerificationStatus.APPROVED, IdentityVerificationDecider.SMILE_ID, Instant.now());
        rider("r-partial", "Grace", "Oyekanmi", null);
        verificationCheckRepository.save(VerificationCheck.builder().driverId("r-partial").checkType(VerificationCheckType.NIN_FACE)
                .subjectKey("coryd-nin-partial").outcome(VerificationCheckOutcome.PARTIAL_NAME_MATCH).reviewedAt(Instant.now())
                .reviewedBy("someone").build());

        assertThatThrownBy(() -> service.reject("r-partial", new IdentityActionRequest(null, "Not them", null), OPS, OPS_ID, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void anApprovalOpsMadeCanBeReversed() {
        everyTab();
        service.approve("r-review", null, OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(service.reject("r-review", new IdentityActionRequest(null, "Approved by mistake - the NIN belongs to someone else", null),
                OPS, OPS_ID, UserRole.OPS_ADMIN).status()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(service.history("r-review", UserRole.OPS_ADMIN)).extracting(VerificationDecisionHistoryItem::action)
                .containsExactly(VerificationDecisionAction.REJECTED, VerificationDecisionAction.OVERRIDE_APPROVED);
    }

    // ---- reset tries --------------------------------------------------------------------------------------------

    @Test
    void resettingTriesLetsALockedOutPersonVerifyAgain() {
        everyTab();

        IdentityReviewDetail detail = service.resetAttempts("r-locked", new IdentityActionRequest("Called in - camera was broken", null, null),
                OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(detail.attempts().used()).isZero();
        assertThat(detail.attempts().left()).isEqualTo(5);
        assertThat(detail.attempts().lockedOut()).isFalse();
        assertThat(detail.tab()).isEqualTo(IdentityReviewTab.FAILED);
        assertThat(detail.status()).as("status doesn't move").isEqualTo(VerificationStatus.PENDING);
        assertThat(detail.history()).singleElement().satisfies(h -> {
            assertThat(h.action()).isEqualTo(VerificationDecisionAction.ATTEMPTS_RESET);
            assertThat(h.previousStatus()).isEqualTo(VerificationStatus.PENDING);
            assertThat(h.status()).isEqualTo(VerificationStatus.PENDING);
            assertThat(h.note()).isEqualTo("Called in - camera was broken");
        });
        assertThat(lastEvent().getAction()).isEqualTo("ATTEMPTS_RESET");
        assertThat(service.counts(UserRole.OPS_ADMIN, null, null).lockedOut()).isEqualTo(1);

        assertThat(service.resetAttempts("d-lookup", null, OPS, OPS_ID, UserRole.OPS_ADMIN).attempts().left())
                .as("a driver's NIN lookups too").isEqualTo(3);
    }

    @Test
    void nothingToResetForSomeoneVerifiedOrWithNoTriesUsed() {
        everyTab();

        assertThatThrownBy(() -> service.resetAttempts("r-approved", null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .hasMessage("This person's identity is already verified - there are no tries to give back");
        assertThatThrownBy(() -> service.resetAttempts("r-rejected", null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .hasMessage("No tries have been used - there's nothing to reset");
    }

    @Test
    void theSandboxNeverLocksAnyoneOut() {
        everyTab();
        properties.setSandbox(true);

        assertThat(service.counts(UserRole.OPS_ADMIN, null, null).lockedOut()).isZero();
        assertThat(row("r-locked").tab()).isEqualTo(IdentityReviewTab.FAILED);
    }

    // ---- only ops -----------------------------------------------------------------------------------------------

    @Test
    void onlyOpsAdmins() {
        everyTab();
        IdentityActionRequest any = new IdentityActionRequest("n", "r", null);

        assertThatThrownBy(() -> service.list(UserRole.RIDER, null, null, null, null, null, 0, 20)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.counts(UserRole.DRIVER, null, null)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.detail("r-review", UserRole.RIDER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.history("r-review", UserRole.RIDER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.approve("r-review", any, OPS, OPS_ID, UserRole.DRIVER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.reject("r-review", any, OPS, OPS_ID, UserRole.RIDER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.resetAttempts("r-locked", any, OPS, OPS_ID, UserRole.RIDER)).isInstanceOf(UnauthorizedException.class);
        assertThat(identityVerificationRepository.findByUserId("r-review").orElseThrow().getStatus()).isEqualTo(VerificationStatus.IN_REVIEW);
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    /** One person per tab, plus a second failed one (ops-rejected) and a locked-out driver on the NIN lookup. */
    private void everyTab() {
        Instant t = Instant.parse("2026-10-01T08:00:00Z");
        identity("r-approved", VerificationStatus.APPROVED, IdentityVerificationDecider.SMILE_ID, t);
        rider("r-approved", "Ada", "Obi", "08031110001");
        smile("r-approved", SmileIdCheckType.NIN, 1, null, false);

        identity("r-failed", VerificationStatus.PENDING, null, t.plusSeconds(60));
        rider("r-failed", "Bola", "Ige", "08031110002");
        smile("r-failed", SmileIdCheckType.NIN, 1, SmileIdRetryReason.FACE_MISMATCH, false);
        result("r-failed", VerificationCheckType.NIN_FACE, VerificationCheckOutcome.DETAILS_MISMATCH);

        identity("r-review", VerificationStatus.IN_REVIEW, null, t.plusSeconds(120));
        rider("r-review", "Chidi", "Eze", "08031110003");
        smile("r-review", SmileIdCheckType.NIN, 1, null, false);
        result("r-review", VerificationCheckType.NIN_FACE, VerificationCheckOutcome.NAME_MISMATCH);

        identity("r-rejected", VerificationStatus.REJECTED, OPS_ID, t.plusSeconds(180));
        rider("r-rejected", "Dayo", "Ola", "08031110004");

        identity("r-pending", VerificationStatus.PENDING, null, t.plusSeconds(240));
        rider("r-pending", "Efe", "Oghene", "08031110005");
        smile("r-pending", SmileIdCheckType.NIN, 0, null, true);

        identity("r-locked", VerificationStatus.PENDING, null, t.plusSeconds(300));
        rider("r-locked", "Funke", "Ade", "08031110006");
        smile("r-locked", SmileIdCheckType.NIN, 5, SmileIdRetryReason.FACE_MISMATCH, false);

        identity("d-lookup", VerificationStatus.PENDING, null, t.plusSeconds(360));
        driverProfileRepository.save(DriverProfile.builder().userId("d-lookup").firstName("Gbenga").lastName("Oni")
                .governmentIdStatus(VerificationStatus.PENDING).ninStatus(VerificationStatus.PENDING).build());
        smile("d-lookup", SmileIdCheckType.NIN_LOOKUP, 3, SmileIdRetryReason.NIN_NOT_FOUND, false);
    }

    private void identity(String userId, VerificationStatus status, String decidedBy, Instant submittedAt) {
        identityVerificationRepository.save(IdentityVerification.builder().userId(userId).status(status).ninLast4("1234")
                .submittedAt(submittedAt).decidedBy(decidedBy).decidedAt(decidedBy == null ? null : submittedAt.plusSeconds(30))
                .rejectionReason(status == VerificationStatus.REJECTED ? "Blurry NIN slip" : null).build());
    }

    private void rider(String userId, String first, String last, String phone) {
        riderProfileRepository.save(RiderProfile.builder().userId(userId).firstName(first).lastName(last).build());
        if (phone != null) {
            riderIndexRepository.save(RiderIndex.builder().riderId(userId).refKey(userId.replace("-", "")).firstName(first).lastName(last)
                    .nameKey((first + " " + last).toLowerCase()).phone(phone).phoneDigits(phone.replaceAll("[^0-9]", ""))
                    .directoryStatus(RiderDirectoryStatus.ACTIVE).build());
        }
    }

    private void smile(String userId, SmileIdCheckType type, int attempts, SmileIdRetryReason retry, boolean inProgress) {
        smileIdCheckRepository.save(SmileIdCheck.builder().userId(userId).checkType(type).checkId("coryd-" + userId)
                .attempts(attempts).retryReason(retry).startedAt(inProgress ? Instant.now() : null).build());
    }

    private void result(String userId, VerificationCheckType type, VerificationCheckOutcome outcome) {
        verificationCheckRepository.save(VerificationCheck.builder().driverId(userId).checkType(type).subjectKey("coryd-nin-" + userId)
                .outcome(outcome).providerSummary(outcome + " - Smile ID job job-" + userId).providerReference("job-" + userId).build());
    }

    private List<String> ids(IdentityReviewTab tab) {
        return service.list(UserRole.OPS_ADMIN, tab, null, null, null, null, 0, 50).content().stream().map(IdentityReviewRow::userId).toList();
    }

    private IdentityReviewRow row(String userId) {
        return service.list(UserRole.OPS_ADMIN, null, null, null, null, null, 0, 50).content().stream()
                .filter(r -> r.userId().equals(userId)).findFirst().orElseThrow();
    }

    private IdentityVerificationChangedEvent lastEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaTopics.IDENTITY_VERIFICATION_CHANGED), captor.capture());
        List<Object> all = captor.getAllValues();
        return (IdentityVerificationChangedEvent) all.get(all.size() - 1);
    }
}
