package org.coryd.profile.service;

import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.ReviewMark;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverRejectedEvent;
import org.coryd.common.kafka.events.VerificationItemDecidedEvent;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.dto.LicenceAttempts;
import org.coryd.profile.dto.OpsAccountProfile;
import org.coryd.profile.dto.OpsDetailsEditRequest;
import org.coryd.profile.dto.OpsEditResult;
import org.coryd.profile.dto.OpsVehicleEditRequest;
import org.coryd.profile.dto.ReviewMarkRequest;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.entity.EmergencyContact;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.RiderProfileRepository;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ops console's account screens, profile-service's half: one person's profile in one call, ops editing name / email
 * and a driver's vehicle (with the rule that changing an approved car's identity sends it back to review), and giving a
 * driver their licence tries back. Against a throwaway Postgres container.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({OpsAccountService.class, LicenceAttemptsService.class, VerificationAggregationService.class, VerificationReviewService.class,
        DriverProfileService.class, VehicleService.class, DriverDocumentService.class, EmergencyContactService.class,
        RiderProfileService.class, ReplacedFiles.class, DriverIndexService.class, DriverIndexer.class})
class OpsAccountIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OPS = "Ada Ops";
    private static final String OPS_ID = "0b5e0000-0000-0000-0000-000000000001";

    @Autowired OpsAccountService opsAccountService;
    @Autowired LicenceAttemptsService licenceAttemptsService;
    @Autowired VerificationAggregationService verification;
    @Autowired VerificationReviewService review;
    @Autowired DriverProfileService driverProfileService;
    @Autowired VehicleService vehicleService;
    @Autowired DriverDocumentService driverDocumentService;
    @Autowired DriverProfileRepository driverProfileRepository;
    @Autowired RiderProfileRepository riderProfileRepository;
    @Autowired DriverIndexRepository driverIndexRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DriverDocumentRepository driverDocumentRepository;
    @Autowired EmergencyContactRepository emergencyContactRepository;
    @Autowired VerificationReviewMarkRepository markRepository;
    @Autowired VerificationDecisionLogRepository decisionLogRepository;
    @Autowired VerificationCheckRepository verificationCheckRepository;
    @Autowired SmileIdCheckRepository smileIdCheckRepository;
    @Autowired IdentityVerificationRepository identityVerificationRepository;

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
        identityVerificationRepository.deleteAll();
        riderProfileRepository.deleteAll();
        driverProfileRepository.deleteAll();
        driverIndexRepository.deleteAll();
        when(kafkaTemplate.send(anyString(), any())).thenReturn(new CompletableFuture<>());
    }

    // ---- the profile in one call --------------------------------------------------------------------------------

    @Test
    void aDriverWhoAlsoRidesComesBackWithEverythingProfileServiceHolds() {
        submitAll("d-1");
        approveAll("d-1");
        riderProfileRepository.save(RiderProfile.builder().userId("d-1").firstName("Emeka").lastName("Okafor").email("e@x.ng").build());
        emergencyContactRepository.save(EmergencyContact.builder().riderId("d-1").name("Ngozi").phone("08011112222").primary(true).build());

        OpsAccountProfile profile = opsAccountService.profile("d-1");

        assertThat(profile.driver().verificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(profile.driver().items()).extracting(OpsAccountProfile.Item::item).containsExactly(ReviewableItem.values());
        assertThat(profile.driver().licenceNumber()).isEqualTo("LAG12345678");
        assertThat(profile.driver().licenceAttempts().left()).isEqualTo(SmileIdCheck.MAX_ATTEMPTS);
        assertThat(profile.rider().email()).isEqualTo("e@x.ng");
        assertThat(profile.vehicles()).singleElement().satisfies(v -> {
            assertThat(v.plateNumber()).isEqualTo("P-d-1");
            assertThat(v.seatCapacity()).isEqualTo(4);
            assertThat(v.status()).isEqualTo(VerificationStatus.APPROVED);
            assertThat(v.primary()).isTrue();
        });
        assertThat(profile.emergencyContacts()).singleElement().satisfies(c -> assertThat(c.primary()).isTrue());
        assertThat(profile.identity()).as("never started a NIN check").isNull();

        assertThatThrownBy(() -> opsAccountService.profile("nobody")).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- name and email -----------------------------------------------------------------------------------------

    @Test
    void nameAndEmailChangeOnBothProfilesAndTheAccountWithBeforeAndAfter() {
        submitAll("d-1");
        riderProfileRepository.save(RiderProfile.builder().userId("d-1").firstName("Emeka").lastName("Okafor").build());

        OpsEditResult result = opsAccountService.editDetails("d-1", new OpsDetailsEditRequest(null, " Okafor-Bello ", "Emeka@Mail.NG"));

        assertThat(result.changes()).containsExactly(new OpsEditResult.Change("lastName", "Okafor", "Okafor-Bello"),
                new OpsEditResult.Change("email", null, "emeka@mail.ng"));
        assertThat(driverProfileRepository.findByUserId("d-1").orElseThrow().getLastName()).isEqualTo("Okafor-Bello");
        assertThat(riderProfileRepository.findByUserId("d-1").orElseThrow().getLastName()).isEqualTo("Okafor-Bello");
        assertThat(riderProfileRepository.findByUserId("d-1").orElseThrow().getEmail()).isEqualTo("emeka@mail.ng");
        verify(identityServiceClient).syncName("d-1", "Emeka", "Okafor-Bello");

        assertThatThrownBy(() -> opsAccountService.editDetails("d-1", new OpsDetailsEditRequest("Emeka", null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Nothing to change - these are already the details on the account");
        assertThatThrownBy(() -> opsAccountService.editDetails("d-1", new OpsDetailsEditRequest(null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Send the first name, last name or email to change");
        assertThatThrownBy(() -> opsAccountService.editDetails("d-1", new OpsDetailsEditRequest("  ", null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("The first name can't be blank");
        assertThatThrownBy(() -> opsAccountService.editDetails("nobody", new OpsDetailsEditRequest("A", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- the vehicle --------------------------------------------------------------------------------------------

    @Test
    void changingAnApprovedCarsPlateSendsItBackToReviewAndStopsPosting() {
        submitAll("d-1");
        approveAll("d-1");
        Vehicle car = vehicleRepository.findByDriverId("d-1").get(0);
        clearInvocations(kafkaTemplate);

        OpsEditResult result = opsAccountService.editVehicle("d-1", car.getId(),
                new OpsVehicleEditRequest(null, null, null, null, "kja-100-aa", null), OPS, OPS_ID);

        assertThat(result.changes()).containsExactly(new OpsEditResult.Change("plateNumber", "P-d-1", "KJA-100-AA"));
        assertThat(result.sentBackToReview()).isTrue();
        assertThat(result.vehicle().status()).isEqualTo(VerificationStatus.IN_REVIEW);
        assertThat(verification.overallStatus("d-1")).isEqualTo(VerificationStatus.IN_REVIEW);
        assertThat(review.history("d-1", UserRole.OPS_ADMIN).get(0)).satisfies(h -> {
            assertThat(h.action()).isEqualTo(VerificationDecisionAction.SENT_BACK_TO_REVIEW);
            assertThat(h.subject()).isEqualTo(DecisionSubject.VEHICLE);
            assertThat(h.previousStatus()).isEqualTo(VerificationStatus.APPROVED);
            assertThat(h.status()).isEqualTo(VerificationStatus.IN_REVIEW);
            assertThat(h.decidedBy()).isEqualTo(OPS);
            assertThat(h.decidedById()).isEqualTo(OPS_ID);
            assertThat(h.note()).contains("plateNumber P-d-1 -> KJA-100-AA");
        });
        ArgumentCaptor<Object> rejected = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.DRIVER_REJECTED), rejected.capture());
        assertThat(((DriverRejectedEvent) rejected.getValue()).getRejectionReason()).isEqualTo("Vehicle details changed - back under review");
        VerificationItemDecidedEvent told = lastItemEvent();
        assertThat(told.getAction()).isEqualTo("SENT_BACK_TO_REVIEW");
        assertThat(told.getItem()).isEqualTo("VEHICLE");
    }

    @Test
    void colourAndSeatsChangeWithoutAReviewAndPlatesStayUnique() {
        submitAll("d-1");
        approveAll("d-1");
        submitAll("d-2");
        Vehicle car = vehicleRepository.findByDriverId("d-1").get(0);
        clearInvocations(kafkaTemplate);

        OpsEditResult result = opsAccountService.editVehicle("d-1", car.getId(),
                new OpsVehicleEditRequest(null, null, null, "Silver", null, 3), OPS, OPS_ID);
        assertThat(result.changes()).extracting(OpsEditResult.Change::field).containsExactly("color", "seatCapacity");
        assertThat(result.sentBackToReview()).isFalse();
        assertThat(verification.overallStatus("d-1")).isEqualTo(VerificationStatus.APPROVED);
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.DRIVER_REJECTED), any());

        assertThatThrownBy(() -> opsAccountService.editVehicle("d-1", car.getId(),
                new OpsVehicleEditRequest(null, null, null, null, "P-d-2", null), OPS, OPS_ID))
                .isInstanceOf(BadRequestException.class).hasMessage("A vehicle with this plate number is already registered");
        assertThatThrownBy(() -> opsAccountService.editVehicle("d-1", car.getId(),
                new OpsVehicleEditRequest(null, null, null, "Silver", null, null), OPS, OPS_ID))
                .isInstanceOf(BadRequestException.class).hasMessage("Nothing to change - these are already the vehicle's details");
        assertThatThrownBy(() -> opsAccountService.editVehicle("d-2", car.getId(),
                new OpsVehicleEditRequest("Honda", null, null, null, null, null), OPS, OPS_ID))
                .as("someone else's car").isInstanceOf(ResourceNotFoundException.class);

        OpsEditResult pending = opsAccountService.editVehicle("d-2", vehicleRepository.findByDriverId("d-2").get(0).getId(),
                new OpsVehicleEditRequest("Honda", "Accord", 2018, null, null, null), OPS, OPS_ID);
        assertThat(pending.sentBackToReview()).as("not approved yet - nothing to send back").isFalse();
        assertThat(pending.vehicle().status()).isEqualTo(VerificationStatus.IN_REVIEW);
    }

    // ---- licence tries ------------------------------------------------------------------------------------------

    @Test
    void aLockedOutDriverGetsTheirLicenceTriesBackAndIsTold() {
        driverProfileService.updateMine("d-1", new UpdateDriverProfileRequest("Emeka", "Okafor", null, "e@x.ng", LocalDate.of(1990, 1, 1),
                null, null, "LAG12345678", null, null));
        smileIdCheckRepository.save(SmileIdCheck.builder().userId("d-1").checkType(SmileIdCheckType.DRIVERS_LICENCE)
                .checkId("coryd-dl-1").attempts(SmileIdCheck.MAX_ATTEMPTS).build());
        assertThat(licenceAttemptsService.attempts("d-1").lockedOut()).isTrue();

        assertThatThrownBy(() -> licenceAttemptsService.reset("d-1", null, OPS, OPS_ID, UserRole.DRIVER))
                .isInstanceOf(UnauthorizedException.class);
        LicenceAttempts after = licenceAttemptsService.reset("d-1", "Called the driver - camera was broken", OPS, OPS_ID, UserRole.OPS_ADMIN);

        assertThat(after.used()).isZero();
        assertThat(after.left()).isEqualTo(SmileIdCheck.MAX_ATTEMPTS);
        assertThat(after.lockedOut()).isFalse();
        assertThat(after.canReset()).isFalse();
        assertThat(review.history("d-1", UserRole.OPS_ADMIN).get(0)).satisfies(h -> {
            assertThat(h.action()).isEqualTo(VerificationDecisionAction.ATTEMPTS_RESET);
            assertThat(h.subject()).isEqualTo(DecisionSubject.GOVERNMENT_ID);
            assertThat(h.status()).isEqualTo(h.previousStatus());
            assertThat(h.note()).isEqualTo("Called the driver - camera was broken");
            assertThat(h.decidedById()).isEqualTo(OPS_ID);
        });
        VerificationItemDecidedEvent told = lastItemEvent();
        assertThat(told.getAction()).isEqualTo("ATTEMPTS_RESET");
        assertThat(told.getItem()).isEqualTo("GOVERNMENT_ID");

        assertThatThrownBy(() -> licenceAttemptsService.reset("d-1", null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class).hasMessage("No tries have been used - there's nothing to reset");
        assertThatThrownBy(() -> licenceAttemptsService.reset("nobody", null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aVerifiedLicenceHasNoTriesToGiveBack() {
        submitAll("d-1");
        approveAll("d-1");
        smileIdCheckRepository.save(SmileIdCheck.builder().userId("d-1").checkType(SmileIdCheckType.DRIVERS_LICENCE)
                .checkId("coryd-dl-1").attempts(2).build());

        assertThat(licenceAttemptsService.attempts("d-1").canReset()).isFalse();
        assertThatThrownBy(() -> licenceAttemptsService.reset("d-1", null, OPS, OPS_ID, UserRole.OPS_ADMIN))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This driver's licence is already verified - there are no tries to give back");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

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
        for (ReviewableItem item : ReviewableItem.values()) {
            review.mark(driverId, item, new ReviewMarkRequest(ReviewMark.VERIFIED, null, OPS), null, UserRole.OPS_ADMIN);
        }
        review.approve(driverId, null, OPS, OPS_ID, UserRole.OPS_ADMIN);
    }

    private VerificationItemDecidedEvent lastItemEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaTopics.VERIFICATION_ITEM_DECIDED), captor.capture());
        List<Object> all = captor.getAllValues();
        return (VerificationItemDecidedEvent) all.get(all.size() - 1);
    }
}
