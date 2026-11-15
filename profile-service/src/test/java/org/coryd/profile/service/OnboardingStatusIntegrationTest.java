package org.coryd.profile.service;

import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.OnboardingStep;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.entity.VerificationDecisionLog;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.RiderIndexRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * What the onboarding reminders and the verification digest read from profile-service, against a throwaway Postgres: each
 * rider's and driver's next steps (profile, NIN incl. Smile ID failure and lock-out, licence, vehicle, documents incl.
 * rejections and resubmission requests), what is with ops instead, who has finished - and who is waiting on an officer.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({OnboardingStatusService.class, VerificationWaitingService.class, RiderIdentityStatuses.class, VerificationAggregationService.class,
        IdentityReviewService.class, IdentityVerificationService.class, IdentityVerificationDecider.class, SmileIdProperties.class,
        DriverIndexService.class, DriverIndexer.class, RiderIndexService.class, RiderIndexer.class})
class OnboardingStatusIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OnboardingStatusService service;
    @Autowired VerificationWaitingService waiting;
    @Autowired SmileIdProperties properties;
    @Autowired RiderProfileRepository riders;
    @Autowired DriverProfileRepository drivers;
    @Autowired IdentityVerificationRepository identities;
    @Autowired SmileIdCheckRepository smileChecks;
    @Autowired VerificationCheckRepository checks;
    @Autowired VehicleRepository vehicles;
    @Autowired DriverDocumentRepository documents;
    @Autowired VerificationDecisionLogRepository decisions;
    @Autowired DriverIndexRepository driverIndex;
    @Autowired RiderIndexRepository riderIndex;

    @MockBean VerifiedIdentityService verifiedIdentityService;
    @MockBean DriverIdentity driverIdentity;
    @MockBean LicenceReviewService licenceReviewService;
    @MockBean IdentityServiceClient identityServiceClient;
    @MockBean CloudinaryService cloudinaryService;
    @MockBean NinLookupService ninLookupService;
    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void cleanSlate() {
        decisions.deleteAll();
        checks.deleteAll();
        smileChecks.deleteAll();
        identities.deleteAll();
        documents.deleteAll();
        vehicles.deleteAll();
        driverIndex.deleteAll();
        riderIndex.deleteAll();
        riders.deleteAll();
        drivers.deleteAll();
        properties.setSandbox(false);
        when(kafkaTemplate.send(anyString(), any())).thenReturn(new CompletableFuture<>());
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
    }

    // ---- riders --------------------------------------------------------------------------------------------------

    @Test
    void aRidersNextStepIsTheirProfileThenTheirNin() {
        OnboardingStatusService.RoleStatus nothing = rider("r-new");
        assertThat(nothing.complete()).isFalse();
        assertThat(nothing.steps()).extracting(OnboardingStatusService.Step::step).containsExactly(OnboardingStep.PROFILE, OnboardingStep.NIN);
        assertThat(nothing.lastProgressAt()).isNull();

        riders.save(RiderProfile.builder().userId("r-new").firstName("Ada").lastName("Obi").build());
        OnboardingStatusService.RoleStatus profiled = rider("r-new");
        assertThat(profiled.steps()).containsExactly(new OnboardingStatusService.Step(OnboardingStep.NIN, OnboardingStep.Reason.NOT_DONE));
        assertThat(profiled.lastProgressAt()).as("making the profile is progress").isNotNull();
    }

    @Test
    void ninFailuresAskForAnotherTryAndALockOutForSupport() {
        profiledRider("r-failed");
        identity("r-failed", VerificationStatus.PENDING);
        smile("r-failed", SmileIdCheckType.NIN, 1, SmileIdRetryReason.FACE_MISMATCH, false);
        assertThat(rider("r-failed").steps()).containsExactly(new OnboardingStatusService.Step(OnboardingStep.NIN, OnboardingStep.Reason.RETRY));

        profiledRider("r-rejected");
        identity("r-rejected", VerificationStatus.REJECTED);
        assertThat(rider("r-rejected").steps()).containsExactly(new OnboardingStatusService.Step(OnboardingStep.NIN, OnboardingStep.Reason.RETRY));

        profiledRider("r-locked");
        identity("r-locked", VerificationStatus.PENDING);
        smile("r-locked", SmileIdCheckType.NIN, 5, SmileIdRetryReason.FACE_MISMATCH, false);
        assertThat(rider("r-locked").steps())
                .containsExactly(new OnboardingStatusService.Step(OnboardingStep.NIN_LOCKED_OUT, OnboardingStep.Reason.LOCKED_OUT));

        profiledRider("r-midway");
        identity("r-midway", VerificationStatus.PENDING);
        assertThat(rider("r-midway").steps()).as("closed the app mid-check")
                .containsExactly(new OnboardingStatusService.Step(OnboardingStep.NIN, OnboardingStep.Reason.NOT_DONE));
    }

    @Test
    void aRiderWaitingOnOpsOrSmileIdHasNothingToDoAndAVerifiedOneIsDone() {
        profiledRider("r-review");
        identity("r-review", VerificationStatus.IN_REVIEW);
        OnboardingStatusService.RoleStatus review = rider("r-review");
        assertThat(review.steps()).isEmpty();
        assertThat(review.waitingOnOps()).isTrue();
        assertThat(review.complete()).isFalse();

        profiledRider("r-checking");
        identity("r-checking", VerificationStatus.PENDING);
        smile("r-checking", SmileIdCheckType.NIN, 0, null, true);
        assertThat(rider("r-checking").steps()).as("Smile ID is still checking").isEmpty();
        assertThat(rider("r-checking").waitingOnOps()).isTrue();

        profiledRider("r-done");
        identity("r-done", VerificationStatus.APPROVED);
        OnboardingStatusService.RoleStatus done = rider("r-done");
        assertThat(done.complete()).isTrue();
        assertThat(done.steps()).isEmpty();
        assertThat(done.waitingOnOps()).isFalse();
    }

    // ---- drivers -------------------------------------------------------------------------------------------------

    @Test
    void aNewDriverIsAskedForEverythingInOrder() {
        assertThat(driver("d-new").steps()).extracting(OnboardingStatusService.Step::step).containsExactly(OnboardingStep.PROFILE,
                OnboardingStep.NIN, OnboardingStep.LICENCE, OnboardingStep.VEHICLE, OnboardingStep.INSURANCE, OnboardingStep.ROADWORTHINESS);
    }

    @Test
    void aDriverIsRemindedOnlyOfWhatIsTheirsWhileTheRestIsWithOps() {
        driverProfile("d-1", VerificationStatus.IN_REVIEW);
        identity("d-1", VerificationStatus.APPROVED);
        vehicle("d-1", VerificationStatus.IN_REVIEW, "AAA-111");
        document("d-1", DocumentType.INSURANCE, VerificationStatus.APPROVED);
        OnboardingStatusService.RoleStatus status = driver("d-1");
        assertThat(status.steps()).containsExactly(new OnboardingStatusService.Step(OnboardingStep.ROADWORTHINESS, OnboardingStep.Reason.NOT_DONE));
        assertThat(status.waitingOnOps()).isTrue();
        assertThat(status.complete()).isFalse();

        document("d-1", DocumentType.ROADWORTHINESS_CERT, VerificationStatus.IN_REVIEW);
        OnboardingStatusService.RoleStatus submitted = driver("d-1");
        assertThat(submitted.steps()).as("everything submitted - ops' turn").isEmpty();
        assertThat(submitted.waitingOnOps()).isTrue();
        assertThat(submitted.lastProgressAt()).isAfterOrEqualTo(status.lastProgressAt());
    }

    @Test
    void rejectedDocumentsAndResubmissionRequestsAreTheDriversTurnAgain() {
        driverProfile("d-2", VerificationStatus.APPROVED);
        identity("d-2", VerificationStatus.APPROVED);
        vehicle("d-2", VerificationStatus.REJECTED, "BBB-222");
        document("d-2", DocumentType.INSURANCE, VerificationStatus.REJECTED);
        document("d-2", DocumentType.ROADWORTHINESS_CERT, VerificationStatus.EXPIRED);
        decisions.save(VerificationDecisionLog.builder().driverId("d-2").item(DecisionSubject.INSURANCE).status(VerificationStatus.REJECTED)
                .action(VerificationDecisionAction.RESUBMISSION_REQUESTED).decidedBy("Ada Ops").note("Blurry").build());
        decisions.save(VerificationDecisionLog.builder().driverId("d-2").item(DecisionSubject.VEHICLE).status(VerificationStatus.REJECTED)
                .action(VerificationDecisionAction.REJECTED).decidedBy("Ada Ops").note("Wrong car").build());

        assertThat(driver("d-2").steps()).containsExactly(
                new OnboardingStatusService.Step(OnboardingStep.VEHICLE, OnboardingStep.Reason.REJECTED),
                new OnboardingStatusService.Step(OnboardingStep.INSURANCE, OnboardingStep.Reason.RESUBMIT),
                new OnboardingStatusService.Step(OnboardingStep.ROADWORTHINESS, OnboardingStep.Reason.EXPIRED));
    }

    @Test
    void aLicenceSmileIdFailedIsATryAgainAndOneStillBeingCheckedIsNot() {
        driverProfile("d-3", VerificationStatus.PENDING);
        identity("d-3", VerificationStatus.APPROVED);
        smile("d-3", SmileIdCheckType.DRIVERS_LICENCE, 1, SmileIdRetryReason.FACE_MISMATCH, false);
        assertThat(driver("d-3").steps()).first()
                .isEqualTo(new OnboardingStatusService.Step(OnboardingStep.LICENCE, OnboardingStep.Reason.RETRY));

        driverProfile("d-4", VerificationStatus.PENDING);
        identity("d-4", VerificationStatus.APPROVED);
        smile("d-4", SmileIdCheckType.DRIVERS_LICENCE, 0, null, true);
        OnboardingStatusService.RoleStatus checking = driver("d-4");
        assertThat(checking.steps()).extracting(OnboardingStatusService.Step::step).doesNotContain(OnboardingStep.LICENCE);
        assertThat(checking.waitingOnOps()).isTrue();
    }

    @Test
    void aDriverApprovedOnceHasFinishedAndBothRolesAreReported() {
        DriverProfile profile = driverProfile("d-5", VerificationStatus.APPROVED);
        profile.setVerifiedSince(Instant.now());
        drivers.save(profile);
        profiledRider("d-5");
        identity("d-5", VerificationStatus.APPROVED);

        OnboardingStatusService.Status both = service.status(List.of(new OnboardingStatusService.Person("d-5", List.of("RIDER", "DRIVER")))).get(0);
        assertThat(both.roles()).extracting(OnboardingStatusService.RoleStatus::role).containsExactly("RIDER", "DRIVER");
        assertThat(both.roles()).allSatisfy(r -> assertThat(r.complete()).isTrue());
    }

    @Test
    void aBatchIsCappedAt500() {
        List<OnboardingStatusService.Person> tooMany = IntStream.range(0, 501)
                .mapToObj(i -> new OnboardingStatusService.Person("u-" + i, List.of("RIDER"))).toList();
        assertThatThrownBy(() -> service.status(tooMany)).isInstanceOf(BadRequestException.class);
        assertThat(service.status(List.of())).isEmpty();
    }

    // ---- the verification digest ---------------------------------------------------------------------------------

    @Test
    void theDigestListsQueuedDriversIdentityChecksInReviewAndUnreviewedPartialMatches() {
        driverProfile("4f2a91c0-0000-0000-0000-000000000001", VerificationStatus.IN_REVIEW);
        vehicle("4f2a91c0-0000-0000-0000-000000000001", VerificationStatus.IN_REVIEW, "CCC-333");
        document("4f2a91c0-0000-0000-0000-000000000001", DocumentType.INSURANCE, VerificationStatus.APPROVED);

        profiledRider("0de4b200-0000-0000-0000-000000000002");
        identity("0de4b200-0000-0000-0000-000000000002", VerificationStatus.IN_REVIEW);
        checks.save(VerificationCheck.builder().driverId("0de4b200-0000-0000-0000-000000000002").checkType(VerificationCheckType.NIN_FACE)
                .subjectKey("nin-1").outcome(VerificationCheckOutcome.NAME_MISMATCH).providerSummary("x").build());

        profiledRider("7c34f100-0000-0000-0000-000000000003");
        identity("7c34f100-0000-0000-0000-000000000003", VerificationStatus.APPROVED);
        checks.save(VerificationCheck.builder().driverId("7c34f100-0000-0000-0000-000000000003").checkType(VerificationCheckType.NIN_FACE)
                .subjectKey("nin-2").outcome(VerificationCheckOutcome.PARTIAL_NAME_MATCH).providerSummary("y").build());
        VerificationCheck reviewed = checks.save(VerificationCheck.builder().driverId("7c34f100-0000-0000-0000-000000000003")
                .checkType(VerificationCheckType.NIN_FACE).subjectKey("nin-3").outcome(VerificationCheckOutcome.PARTIAL_NAME_MATCH)
                .providerSummary("z").build());
        reviewed.setReviewedAt(Instant.now());
        checks.save(reviewed);

        VerificationWaitingService.Waiting result = waiting.waiting();
        assertThat(result.drivers()).isEqualTo(1);
        assertThat(result.identityChecks()).isEqualTo(1);
        assertThat(result.partialNameMatches()).isEqualTo(1);

        VerificationWaitingService.Item driver = result.items().stream().filter(i -> i.kind() == VerificationWaitingService.Kind.DRIVER_DOCUMENTS)
                .findFirst().orElseThrow();
        assertThat(driver.ref()).isEqualTo("D-4F2A91");
        assertThat(driver.name()).isEqualTo("Driver Test");
        assertThat(driver.what()).isEqualTo("Driver's licence, Vehicle registration");
        assertThat(driver.waitingSince()).isNotNull();

        VerificationWaitingService.Item identity = result.items().stream().filter(i -> i.kind() == VerificationWaitingService.Kind.IDENTITY_CHECK)
                .findFirst().orElseThrow();
        assertThat(identity.ref()).isEqualTo("R-0DE4B2");
        assertThat(identity.what()).isEqualTo("Identity (NIN) check - name mismatch");

        VerificationWaitingService.Item partial = result.items().stream().filter(i -> i.kind() == VerificationWaitingService.Kind.PARTIAL_NAME_MATCH)
                .findFirst().orElseThrow();
        assertThat(partial.userId()).isEqualTo("7c34f100-0000-0000-0000-000000000003");
        assertThat(partial.what()).startsWith("Partial name match on NIN check");
    }

    @Test
    void anEmptyQueueIsEmpty() {
        profiledRider("r-ok");
        identity("r-ok", VerificationStatus.APPROVED);
        VerificationWaitingService.Waiting result = waiting.waiting();
        assertThat(result.items()).isEmpty();
        assertThat(result.drivers() + result.identityChecks() + result.partialNameMatches()).isZero();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private OnboardingStatusService.RoleStatus rider(String id) {
        return service.status(List.of(new OnboardingStatusService.Person(id, List.of("RIDER")))).get(0).roles().get(0);
    }

    private OnboardingStatusService.RoleStatus driver(String id) {
        return service.status(List.of(new OnboardingStatusService.Person(id, List.of("DRIVER")))).get(0).roles().get(0);
    }

    private void profiledRider(String id) {
        riders.save(RiderProfile.builder().userId(id).firstName("Rider").lastName("Test").build());
    }

    private DriverProfile driverProfile(String id, VerificationStatus licence) {
        return drivers.save(DriverProfile.builder().userId(id).firstName("Driver").lastName("Test").dateOfBirth(LocalDate.of(1990, 1, 1))
                .governmentIdStatus(licence).governmentIdSubmittedAt(licence == VerificationStatus.PENDING ? null : Instant.now().minus(2, ChronoUnit.DAYS))
                .ninStatus(VerificationStatus.PENDING).build());
    }

    private void identity(String id, VerificationStatus status) {
        identities.save(IdentityVerification.builder().userId(id).status(status).ninLast4("1234").submittedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build());
    }

    private void smile(String id, SmileIdCheckType type, int attempts, SmileIdRetryReason retry, boolean inProgress) {
        smileChecks.save(SmileIdCheck.builder().userId(id).checkType(type).checkId("coryd-" + type + "-" + id).attempts(attempts).retryReason(retry)
                .startedAt(inProgress ? Instant.now() : null).build());
    }

    private void vehicle(String id, VerificationStatus status, String plate) {
        vehicles.save(Vehicle.builder().driverId(id).make("Toyota").model("Corolla").year(2015).color("Blue").plateNumber(plate).seatCapacity(4)
                .verificationStatus(status).submittedAt(Instant.now().minus(1, ChronoUnit.DAYS)).build());
    }

    private void document(String id, DocumentType type, VerificationStatus status) {
        documents.save(DriverDocument.builder().driverId(id).documentType(type).documentUrl("https://files/" + type).status(status)
                .submittedAt(Instant.now().minus(1, ChronoUnit.DAYS)).expiresOn(LocalDate.now().plusYears(1)).build());
    }
}
