package org.coryd.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.client.SmileIdClient;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.DriverProfileResponse;
import org.coryd.profile.dto.IdentityVerificationResponse;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.dto.SmileIdSession;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Both Smile ID checks through the one service: who may start, and what each result does to the NIN
 * check (identity_verifications) and the licence check (the driver's GOVERNMENT_ID item).
 */
class SmileIdCheckServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final SmileIdProperties properties = new SmileIdProperties();
    private final SmileIdClient smileIdClient = mock(SmileIdClient.class);

    // In-memory stand-ins for the tables the checks read and write.
    private final Map<String, SmileIdCheck> checks = new HashMap<>();
    private final List<VerificationCheck> recorded = new ArrayList<>();
    private final Map<String, IdentityVerification> verifications = new HashMap<>();
    private final Map<String, DriverProfile> drivers = new HashMap<>();
    private final Map<String, RiderProfile> riders = new HashMap<>();

    private final IdentityVerificationDecider decider = mock(IdentityVerificationDecider.class);
    private final VerificationAggregationService aggregation = mock(VerificationAggregationService.class);
    private SmileIdCheckService service;

    @BeforeEach
    void setUp() {
        properties.setPartnerId("1234");
        properties.setApiKey("key");
        properties.setSandbox(false);
        when(smileIdClient.mintToken()).thenReturn("short-lived");

        SmileIdCheckRepository checkRepo = mock(SmileIdCheckRepository.class);
        when(checkRepo.findByCheckId(anyString())).thenAnswer(i -> checks.values().stream()
                .filter(c -> i.getArgument(0).equals(c.getCheckId())).findFirst());
        when(checkRepo.findByUserIdAndCheckType(anyString(), any())).thenAnswer(i -> Optional.ofNullable(checks.get(i.getArgument(0) + "|" + i.getArgument(1))));
        when(checkRepo.save(any())).thenAnswer(i -> {
            SmileIdCheck c = i.getArgument(0);
            checks.put(c.getUserId() + "|" + c.getCheckType(), c);
            return c;
        });
        VerificationCheckRepository recordRepo = mock(VerificationCheckRepository.class);
        when(recordRepo.findByDriverIdAndCheckTypeAndSubjectKey(anyString(), any(), anyString())).thenAnswer(i -> recorded.stream()
                .filter(r -> r.getDriverId().equals(i.getArgument(0)) && r.getCheckType() == i.getArgument(1)
                        && r.getSubjectKey().equals(i.getArgument(2))).findFirst());
        when(recordRepo.save(any())).thenAnswer(i -> {
            recorded.add(i.getArgument(0));
            return i.getArgument(0);
        });
        IdentityVerificationRepository verificationRepo = mock(IdentityVerificationRepository.class);
        when(verificationRepo.findByUserId(anyString())).thenAnswer(i -> Optional.ofNullable(verifications.get((String) i.getArgument(0))));
        when(verificationRepo.save(any())).thenAnswer(i -> {
            IdentityVerification v = i.getArgument(0);
            verifications.put(v.getUserId(), v);
            return v;
        });
        DriverProfileRepository driverRepo = mock(DriverProfileRepository.class);
        when(driverRepo.findByUserId(anyString())).thenAnswer(i -> Optional.ofNullable(drivers.get((String) i.getArgument(0))));
        when(driverRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        RiderProfileRepository riderRepo = mock(RiderProfileRepository.class);
        when(riderRepo.findByUserId(anyString())).thenAnswer(i -> Optional.ofNullable(riders.get((String) i.getArgument(0))));

        IdentityVerificationService identityService = new IdentityVerificationService(verificationRepo, riderRepo, driverRepo,
                recordRepo, decider, checkRepo);
        NinCheck nin = new NinCheck(verificationRepo, identityService, decider, properties);
        LicenceCheck licence = new LicenceCheck(driverRepo, aggregation, properties);
        service = new SmileIdCheckService(List.of(nin, licence), checkRepo, recordRepo, smileIdClient, properties, json);

        riders.put("rider-1", RiderProfile.builder().userId("rider-1").firstName("Grace").lastName("Amanda").build());
        drivers.put("driver-1", DriverProfile.builder().userId("driver-1").firstName("Tunde").lastName("Bakare")
                .idNumber("ABC12345DE67").governmentIdStatus(VerificationStatus.PENDING).build());
    }

    // ---- shared: opening a check and matching results -----------------------------------------

    @Test
    void opensAJobAndHandsTheAppWhatTheSdkNeeds() {
        SmileIdSession nin = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901");
        SmileIdSession licence = service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null);

        assertThat(nin.checkId()).startsWith("coryd-nin-");
        assertThat(nin.partnerParams()).containsEntry("coryd_check_id", nin.checkId());
        assertThat(nin.idType()).isEqualTo("NIN_V2");
        assertThat(licence.checkId()).startsWith("coryd-dl-");
        assertThat(licence.idType()).isEqualTo("DRIVERS_LICENSE");
        assertThat(nin.token()).isEqualTo("short-lived");
        assertThat(nin.partnerId()).isEqualTo("1234");
        assertThat(nin.country()).isEqualTo("NG");
        assertThat(nin.callbackUrl()).isNull();
        assertThat(check("rider-1", SmileIdCheckType.NIN).waitingForResult()).isTrue();
        assertThat(check("rider-1", SmileIdCheckType.NIN).getAttempts()).as("a try is used by a result, not by opening").isZero();
        assertThat(verifications.get("rider-1").getStatus()).isEqualTo(VerificationStatus.PENDING);
        assertThat(verifications.get("rider-1").getNinLast4()).isEqualTo("8901");
    }

    @Test
    void unavailableUntilSmileIdIsConfigured() {
        properties.setApiKey("");

        assertThatThrownBy(() -> service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901"))
                .hasMessage("Identity checks are unavailable right now - try again later");
        assertThatThrownBy(() -> service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null))
                .hasMessage("Licence checks are unavailable right now - try again later");
        assertThat(checks).isEmpty();
    }

    @Test
    void fiveTriesPerCheck() {
        service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901");
        check("rider-1", SmileIdCheckType.NIN).setAttempts(5);

        assertThatThrownBy(() -> service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901"))
                .hasMessage("Too many attempts - contact CoRyd support to verify your identity");
        assertThat(service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null)).isNotNull();
    }

    @Test
    void anUnknownJobARepeatDeliveryOrAnOldJobIsIgnored() throws Exception {
        String first = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901").checkId();

        service.handle(result("not-ours", "clear", null, nimc("GRACE", "AMANDA")));
        assertThat(recorded).isEmpty();

        String second = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901").checkId();
        service.handle(result(first, "clear", null, nimc("GRACE", "AMANDA")));
        assertThat(recorded).as("a result for the job they replaced").isEmpty();

        service.handle(result(second, "clear", null, nimc("GRACE", "AMANDA")));
        service.handle(result(second, "clear", null, nimc("GRACE", "AMANDA")));
        assertThat(recorded).hasSize(1);
        verify(decider).approve(verifications.get("rider-1"), IdentityVerificationDecider.SMILE_ID);
    }

    @Test
    void anAbandonedCheckStopsShowingAsInProgress() {
        service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901");
        check("rider-1", SmileIdCheckType.NIN).setStartedAt(Instant.now().minus(16, ChronoUnit.MINUTES));

        assertThat(ninResponse().checkInProgress()).isFalse();
        assertThat(ninResponse().status()).isEqualTo(VerificationStatus.PENDING);
    }

    // ---- NIN -------------------------------------------------------------------------------------

    @Nested
    class Nin {

        String job;

        @BeforeEach
        void open() {
            job = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901").checkId();
        }

        @Test
        void clearInTheirNameVerifiesThem() throws Exception {
            service.handle(result(job, "clear", null, nimc("GRACE", "AMANDA")));

            verify(decider).approve(verifications.get("rider-1"), IdentityVerificationDecider.SMILE_ID);
            assertThat(last().getCheckType()).isEqualTo(VerificationCheckType.NIN_FACE);
            assertThat(last().getOutcome()).isEqualTo(VerificationCheckOutcome.MATCH);
            assertThat(last().getProviderReference()).as("Smile ID's own job ID, for the portal").isEqualTo("smile-" + job);
            assertThat(last().getSubjectKey()).isEqualTo(job);
            assertThat(check("rider-1", SmileIdCheckType.NIN).getAttempts()).isEqualTo(1);
        }

        @Test
        void namesTheOtherWayRoundStillMatch() throws Exception {
            service.handle(result(job, "clear", null, nimc("AMANDA", "GRACE")));

            verify(decider).approve(any(), anyString());
        }

        @Test
        void someoneElsesNinGoesToOps() throws Exception {
            service.handle(result(job, "clear", null, nimc("TUNDE", "BAKARE")));

            verify(decider, never()).approve(any(), anyString());
            assertThat(verifications.get("rider-1").getStatus()).isEqualTo(VerificationStatus.IN_REVIEW);
            assertThat(last().getOutcome()).isEqualTo(VerificationCheckOutcome.NAME_MISMATCH);
            assertThat(last().getProviderSummary()).contains("TUNDE BAKARE").contains("Grace Amanda").contains(job);
        }

        @Test
        void aFaceMismatchOrUnknownNinIsTheirsToRetryAndUsesATry() throws Exception {
            service.handle(result(job, "block", "face_verification_failed", null));

            IdentityVerificationResponse response = ninResponse();
            assertThat(response.status()).isEqualTo(VerificationStatus.PENDING);
            assertThat(response.retryReason()).isEqualTo(SmileIdRetryReason.FACE_MISMATCH);
            assertThat(response.checkInProgress()).isFalse();
            assertThat(response.attemptsLeft()).isEqualTo(4);

            String again = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901").checkId();
            assertThat(ninResponse().retryReason()).as("cleared when they try again").isNull();
            service.handle(result(again, "block", "identifier_not_found", null));
            assertThat(ninResponse().retryReason()).isEqualTo(SmileIdRetryReason.NIN_NOT_FOUND);
            assertThat(ninResponse().attemptsLeft()).isEqualTo(3);
        }

        @Test
        void anOutageOrUnusableSelfieCostsNoTry() throws Exception {
            service.handle(result(job, "error", "service_unavailable", null));
            assertThat(ninResponse().retryReason()).isEqualTo(SmileIdRetryReason.TRY_LATER);

            String again = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901").checkId();
            service.handle(result(again, "error", "image_unavailable_or_invalid", null));
            assertThat(ninResponse().retryReason()).isEqualTo(SmileIdRetryReason.UNREADABLE);
            assertThat(ninResponse().attemptsLeft()).isEqualTo(5);
        }

        @Test
        void noNimcPhotoGoesToOpsBecauseRetryingCanNeverWork() throws Exception {
            service.handle(json.readValue("{\"status\":\"error\",\"reason\":\"image_unavailable_or_invalid\","
                    + "\"message\":\"Error: ID authority photo not available.\",\"partner_params\":{\"job_id\":\"smile-" + job + "\",\"coryd_check_id\":\"" + job + "\"}}", SmileIdResult.class));

            assertThat(verifications.get("rider-1").getStatus()).isEqualTo(VerificationStatus.IN_REVIEW);
        }

        @Test
        void spoofsRiskAndAttentionGoToOpsNeverRejected() throws Exception {
            for (String[] r : new String[][]{{"block", "spoof_detected"}, {"block", "high_risk"}, {"attention", "medium_risk"}, {"block", "something_new"}}) {
                verifications.get("rider-1").setStatus(VerificationStatus.PENDING);
                String j = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901").checkId();
                service.handle(result(j, r[0], r[1], null));
                assertThat(verifications.get("rider-1").getStatus()).as(r[1]).isEqualTo(VerificationStatus.IN_REVIEW);
            }
            verify(decider, never()).reject(any(), anyString(), anyString());
        }

        @Test
        void ignoredOnceOpsHasItOrDecided() throws Exception {
            for (VerificationStatus decided : new VerificationStatus[]{VerificationStatus.REJECTED, VerificationStatus.IN_REVIEW, VerificationStatus.APPROVED}) {
                verifications.get("rider-1").setStatus(decided);
                service.handle(result(job, "clear", null, nimc("GRACE", "AMANDA")));
                service.handle(result(job, "block", "face_verification_failed", null));
                assertThat(verifications.get("rider-1").getStatus()).as(decided.name()).isEqualTo(decided);
            }
            verify(decider, never()).approve(any(), anyString());
            assertThat(recorded).isEmpty();
        }

        @Test
        void imageLinksAreNotKeptAndOtherAccountsAreFlagged() throws Exception {
            service.handle(json.readValue("{\"status\":\"clear\",\"partner_params\":{\"job_id\":\"smile-" + job + "\",\"coryd_check_id\":\"" + job + "\"},"
                    + "\"kyc_receipt\":\"https://s3/receipt.pdf\",\"image_links\":{\"selfie_image\":\"https://s3/selfie.jpg\"},"
                    + "\"id_fields\":{\"first_name\":\"GRACE\",\"last_name\":\"AMANDA\",\"photo_url\":\"https://s3/id.jpg\"},"
                    + "\"antifraud\":{\"smile_secure\":{\"suspect_users\":[{\"user_id\":\"user-9\",\"reasons\":[\"same-face\"]}]}}}", SmileIdResult.class));

            assertThat(last().getRawResponse()).doesNotContain("s3/selfie").doesNotContain("s3/receipt").doesNotContain("s3/id.jpg");
            assertThat(last().getProviderSummary()).contains("user-9");
        }

        @Test
        void refusedWhileWithOpsOrVerifiedOrWithoutAName() {
            verifications.get("rider-1").setStatus(VerificationStatus.IN_REVIEW);
            assertThatThrownBy(() -> service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901")).hasMessageContaining("with our team");
            verifications.get("rider-1").setStatus(VerificationStatus.APPROVED);
            assertThatThrownBy(() -> service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901")).hasMessageContaining("already verified");
            riders.put("rider-2", RiderProfile.builder().userId("rider-2").firstName("Grace").build());
            assertThatThrownBy(() -> service.start("rider-2", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901"))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("first and last name");
        }
    }

    // ---- driver's licence -----------------------------------------------------------------------

    @Nested
    class Licence {

        String job;

        @BeforeEach
        void open() {
            job = service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null).checkId();
        }

        @Test
        void aGenuineLicenceInTheirNameWithTheirNumberIsApproved() throws Exception {
            service.handle(result(job, "clear", null, licence("TUNDE", "BAKARE", "abc-12345-de67", "2030-01-01")));

            verify(aggregation).autoApproveGovernmentId("driver-1", LicenceCheck.DECIDED_BY);
            assertThat(last().getCheckType()).isEqualTo(VerificationCheckType.DRIVERS_LICENCE);
            assertThat(last().getOutcome()).isEqualTo(VerificationCheckOutcome.MATCH);
            assertThat(last().getProviderSummary()).contains("valid to 2030-01-01");
        }

        @Test
        void wrongNameNumberOrExpiredGoesToOps() throws Exception {
            String[][] cases = {{"ADA", "OBI", "ABC12345DE67", "2030-01-01", "ADA OBI"},
                    {"TUNDE", "BAKARE", "ZZZ99999", "2030-01-01", "ZZZ99999"},
                    {"TUNDE", "BAKARE", "ABC12345DE67", LocalDate.now().minusDays(1).toString(), "expired"}};
            for (String[] c : cases) {
                drivers.get("driver-1").setGovernmentIdStatus(VerificationStatus.PENDING);
                String j = service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null).checkId();
                service.handle(result(j, "clear", null, licence(c[0], c[1], c[2], c[3])));
                assertThat(drivers.get("driver-1").getGovernmentIdStatus()).as(c[4]).isEqualTo(VerificationStatus.IN_REVIEW);
                assertThat(drivers.get("driver-1").getGovernmentIdSubmittedAt()).isNotNull();
                assertThat(last().getProviderSummary()).contains(c[4]);
            }
            verify(aggregation, never()).autoApproveGovernmentId(anyString(), anyString());
        }

        @Test
        void aFaceMismatchOrWrongDocumentIsTheDriversToRetry() throws Exception {
            service.handle(result(job, "block", "face_verification_failed", null));
            assertThat(licenceResponse().licenceRetryReason()).isEqualTo(SmileIdRetryReason.FACE_MISMATCH);

            String again = service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null).checkId();
            service.handle(result(again, "block", "unsupported_document", null));
            DriverProfileResponse response = licenceResponse();
            assertThat(response.licenceRetryReason()).isEqualTo(SmileIdRetryReason.NOT_A_LICENCE);
            assertThat(response.licenceAttemptsLeft()).isEqualTo(3);
            assertThat(response.licenceCheckInProgress()).isFalse();
            assertThat(response.governmentIdStatus()).isEqualTo(VerificationStatus.PENDING);
        }

        @Test
        void anUnreadablePhotoCostsNoTry() throws Exception {
            service.handle(result(job, "error", "document_unclassifiable", null));

            assertThat(licenceResponse().licenceRetryReason()).isEqualTo(SmileIdRetryReason.UNREADABLE);
            assertThat(licenceResponse().licenceAttemptsLeft()).isEqualTo(5);
        }

        @Test
        void forgeriesPhotocopiesExpiredAndRiskGoToOps() throws Exception {
            for (String[] c : new String[][]{{"block", "document_check_failed"}, {"attention", "document_copy_detected"},
                    {"attention", "document_expired"}, {"block", "document_expired"}, {"block", "spoof_detected"}}) {
                drivers.get("driver-1").setGovernmentIdStatus(VerificationStatus.PENDING);
                String j = service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null).checkId();
                service.handle(result(j, c[0], c[1], null));
                assertThat(drivers.get("driver-1").getGovernmentIdStatus()).as(c[1]).isEqualTo(VerificationStatus.IN_REVIEW);
            }
            verify(aggregation, never()).autoApproveGovernmentId(anyString(), anyString());
        }

        @Test
        void aLicenceOpsRejectedCanBeCheckedAgain() {
            drivers.get("driver-1").setGovernmentIdStatus(VerificationStatus.REJECTED);
            drivers.get("driver-1").setGovernmentIdRejectionReason("Blurry");

            service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null);

            assertThat(drivers.get("driver-1").getGovernmentIdStatus()).isEqualTo(VerificationStatus.PENDING);
            assertThat(drivers.get("driver-1").getGovernmentIdRejectionReason()).isNull();
            assertThat(drivers.get("driver-1").isGovernmentIdResubmitted()).isTrue();
        }

        @Test
        void ignoredOnceOpsHasItOrDecided() throws Exception {
            for (VerificationStatus decided : new VerificationStatus[]{VerificationStatus.APPROVED, VerificationStatus.REJECTED, VerificationStatus.IN_REVIEW}) {
                drivers.get("driver-1").setGovernmentIdStatus(decided);
                service.handle(result(job, "clear", null, licence("TUNDE", "BAKARE", "ABC12345DE67", "2030-01-01")));
                assertThat(drivers.get("driver-1").getGovernmentIdStatus()).isEqualTo(decided);
            }
            verify(aggregation, never()).autoApproveGovernmentId(anyString(), anyString());
            assertThat(recorded).isEmpty();
        }

        @Test
        void refusedWithoutProfileDetailsOrOnceVerified() {
            assertThatThrownBy(() -> service.start("nobody", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null))
                    .hasMessageContaining("Save your driver profile");
            drivers.get("driver-1").setIdNumber(null);
            assertThatThrownBy(() -> service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null))
                    .hasMessageContaining("licence number");
            drivers.get("driver-1").setIdNumber("ABC12345DE67");
            drivers.get("driver-1").setGovernmentIdStatus(VerificationStatus.APPROVED);
            assertThatThrownBy(() -> service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null))
                    .hasMessageContaining("already verified");
        }
    }

    // ---- what is sent to Smile ID: the person on live, the test identity on sandbox ---------------

    @Test
    void liveSendsThePersonsOwnName() {
        SmileIdSession nin = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901");
        SmileIdSession licence = service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null);

        assertThat(nin.sandbox()).isFalse();
        assertThat(nin.userDetails()).isEqualTo(new SmileIdSession.UserDetails("Grace", "Amanda", null));
        assertThat(licence.userDetails()).isEqualTo(new SmileIdSession.UserDetails("Tunde", "Bakare", null));
    }

    @Nested
    class Sandbox {

        @BeforeEach
        void sandbox() {
            properties.setSandbox(true);
        }

        @Test
        void everyCheckIsSentAsTheTestIdentityThatAlwaysSucceeds() {
            SmileIdSession nin = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901");
            SmileIdSession licence = service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null);

            SmileIdSession.UserDetails test = new SmileIdSession.UserDetails("Amina Fatou", "Clearwater", "amina.clearwater@example.com");
            assertThat(nin.sandbox()).isTrue();
            assertThat(nin.userDetails()).isEqualTo(test);
            assertThat(licence.userDetails()).isEqualTo(test);
        }

        @Test
        void theTestIdentitysClearResultApprovesTheRealProfile() throws Exception {
            String job = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901").checkId();

            service.handle(result(job, "clear", null, nimc("AMINA", "CLEARWATER")));

            verify(decider).approve(verifications.get("rider-1"), IdentityVerificationDecider.SMILE_ID);
            assertThat(last().getProviderSummary()).startsWith("[SANDBOX]");
        }

        @Test
        void aVerifiedPersonCanRunTheNinCheckAgainAndAgain() throws Exception {
            verifications.put("rider-1", IdentityVerification.builder().userId("rider-1").status(VerificationStatus.APPROVED).build());

            for (int run = 0; run < 7; run++) {
                SmileIdSession session = service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "1234567890" + (run % 10));
                assertThat(verifications.get("rider-1").getStatus()).as("still verified while the re-check runs").isEqualTo(VerificationStatus.APPROVED);
                assertThat(ninResponse().checkInProgress()).isTrue();
                service.handle(result(session.checkId(), "clear", null, nimc("AMINA", "CLEARWATER")));
                assertThat(ninResponse().checkInProgress()).isFalse();
            }
            verify(decider, org.mockito.Mockito.times(7)).approve(any(), anyString());
        }

        @Test
        void aVerifiedOrInReviewLicenceCanBeCheckedAgain() throws Exception {
            for (VerificationStatus status : new VerificationStatus[]{VerificationStatus.APPROVED, VerificationStatus.IN_REVIEW, VerificationStatus.APPROVED}) {
                drivers.get("driver-1").setGovernmentIdStatus(status);
                String job = service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null).checkId();
                service.handle(result(job, "clear", null, licence("AMINA", "CLEARWATER", "00000000000", "2031-01-15")));
            }
            verify(aggregation, org.mockito.Mockito.times(3)).autoApproveGovernmentId("driver-1", LicenceCheck.DECIDED_BY);
        }

        @Test
        void triesAreNotRationed() {
            service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901");
            check("rider-1", SmileIdCheckType.NIN).setAttempts(50);

            assertThat(service.start("rider-1", UserRole.RIDER, SmileIdCheckType.NIN, "12345678901")).isNotNull();
        }

        @Test
        void theTestLicenceApprovesDespiteItsPlaceholderNumber() throws Exception {
            String job = service.start("driver-1", UserRole.DRIVER, SmileIdCheckType.DRIVERS_LICENCE, null).checkId();

            service.handle(result(job, "clear", null, licence("AMINA", "CLEARWATER", "00000000000", "2031-01-15")));

            verify(aggregation).autoApproveGovernmentId("driver-1", LicenceCheck.DECIDED_BY);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private SmileIdCheck check(String userId, SmileIdCheckType type) {
        return checks.get(userId + "|" + type);
    }

    private VerificationCheck last() {
        return recorded.get(recorded.size() - 1);
    }

    private IdentityVerificationResponse ninResponse() {
        return IdentityVerificationResponse.from(verifications.get("rider-1"), check("rider-1", SmileIdCheckType.NIN));
    }

    private DriverProfileResponse licenceResponse() {
        return DriverProfileResponse.from(drivers.get("driver-1"), null, null, check("driver-1", SmileIdCheckType.DRIVERS_LICENCE));
    }

    private static String nimc(String first, String last) {
        return "{\"first_name\":\"" + first + "\",\"last_name\":\"" + last + "\",\"full_name\":\"" + first + " " + last + "\"}";
    }

    private static String licence(String first, String last, String number, String expires) {
        return "{\"first_name\":\"" + first + "\",\"last_name\":\"" + last + "\",\"full_name\":\"" + first + " " + last
                + "\",\"id_number\":\"" + number + "\",\"id_type\":\"DRIVERS_LICENSE\",\"expiration_date\":\"" + expires + "\"}";
    }

    private SmileIdResult result(String job, String status, String reason, String idFields) throws Exception {
        return json.readValue("{\"status\":\"" + status + "\",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"")
                + ",\"message\":\"m\",\"partner_params\":{\"job_id\":\"smile-" + job + "\",\"coryd_check_id\":\"" + job + "\"},\"id_fields\":"
                + (idFields == null ? "null" : idFields) + "}", SmileIdResult.class);
    }
}
