package org.coryd.profile.service;

import org.coryd.common.enums.DocumentExpirySort;
import org.coryd.common.enums.DocumentExpiryStatus;
import org.coryd.common.enums.DocumentExpiryTab;
import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DocumentExpiringEvent;
import org.coryd.common.kafka.events.DriverApprovedEvent;
import org.coryd.common.kafka.events.DriverRejectedEvent;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.CreateDriverDocumentRequest;
import org.coryd.profile.dto.DocumentExpiryCounts;
import org.coryd.profile.dto.DocumentExpiryNotifyResult;
import org.coryd.profile.dto.DocumentExpiryView;
import org.coryd.profile.dto.ItemDecision;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.DriverIndexRepository;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.coryd.profile.scheduler.DocumentExpiryJob;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Document Expiry Tracker and the nightly expiry job: tabs, counts, search and sorting in the database; ops reminders
 * with their cool-down; expiry stopping the driver posting; reminders sent once with the days actually left; and a renewed
 * document letting the driver post again. Against a throwaway Postgres container.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({VerificationAggregationService.class, DriverProfileService.class, VehicleService.class, DriverDocumentService.class,
        EmergencyContactService.class, RiderProfileService.class, ReplacedFiles.class, DriverIndexService.class, DriverIndexer.class,
        DocumentExpiryService.class, DocumentExpiryJob.class})
class DocumentExpiryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired DocumentExpiryService expiry;
    @Autowired DocumentExpiryJob job;
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

    LocalDate today;

    @BeforeEach
    void cleanSlate() {
        when(ninLookupService.view(any())).thenReturn(new org.coryd.profile.dto.DriverNinView(true, "MATCH", null, null, 3));
        when(kafkaTemplate.send(anyString(), any())).thenReturn(new CompletableFuture<>());
        when(identityServiceClient.contacts(any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(id -> new IdentityServiceClient.Contact(id, phoneOf(id), null, null, null)).toList();
        });
        smileIdCheckRepository.deleteAll();
        driverDocumentRepository.deleteAll();
        vehicleRepository.deleteAll();
        emergencyContactRepository.deleteAll();
        driverProfileRepository.deleteAll();
        driverIndexRepository.deleteAll();
        today = DocumentExpiryService.window().today();
    }

    // ---- the tracker -----------------------------------------------------------------------------

    @Test
    void eachDocumentFallsUnderExactlyOneTabAndTheCountsAddUp() {
        seed();

        DocumentExpiryCounts counts = expiry.counts(null);
        assertThat(counts).isEqualTo(new DocumentExpiryCounts(8, 3, 2, 2, 1, 1));
        assertThat(counts.expiringSoon() + counts.expired() + counts.verified() + counts.notVerified()).isEqualTo(counts.all());

        assertThat(tab(DocumentExpiryTab.EXPIRED)).extracting(DocumentExpiryView::daysLeft).containsExactly(-10L, -1L);
        assertThat(tab(DocumentExpiryTab.EXPIRING_SOON)).extracting(DocumentExpiryView::daysLeft).containsExactly(0L, 3L, 14L);
        assertThat(tab(DocumentExpiryTab.VERIFIED)).extracting(DocumentExpiryView::daysLeft).containsExactly(15L, 200L);
        assertThat(tab(DocumentExpiryTab.VERIFIED)).allMatch(r -> r.status() == DocumentExpiryStatus.VERIFIED && r.verifiedAt() != null);
        assertThat(tab(null)).hasSize(8);
    }

    @Test
    void rowsCarryTheDriverAndWhetherTheyCanBeReminded() {
        seed();

        DocumentExpiryView soon = tab(DocumentExpiryTab.EXPIRING_SOON).get(0);
        assertThat(soon.firstName()).isEqualTo("Emeka");
        assertThat(soon.phone()).isEqualTo("08053636321");
        assertThat(soon.documentType()).isEqualTo(DocumentType.INSURANCE);
        assertThat(soon.notifiable()).isTrue();
        assertThat(soon.notifiedRecently()).isFalse();
        assertThat(tab(DocumentExpiryTab.VERIFIED)).noneMatch(DocumentExpiryView::notifiable);
    }

    @Test
    void searchMatchesNameReferencePhoneAndDocument() {
        seed();

        assertThat(search("fatima")).extracting(DocumentExpiryView::driverId).containsOnly("bbbb2222-2");
        assertThat(search("D-AAAA11")).extracting(DocumentExpiryView::driverId).containsOnly("aaaa1111-1");
        assertThat(search("0805 3636")).extracting(DocumentExpiryView::driverId).containsOnly("aaaa1111-1");
        assertThat(search("roadworth")).extracting(DocumentExpiryView::documentType).containsOnly(DocumentType.ROADWORTHINESS_CERT);
        assertThat(search("insurance")).extracting(DocumentExpiryView::documentType).containsOnly(DocumentType.INSURANCE);
        assertThat(search("100%_")).isEmpty();
        assertThat(expiry.counts("fatima").all()).isEqualTo(search("fatima").size());
    }

    @Test
    void sortingHappensInTheDatabaseWithBlanksLast() {
        seed();
        expiry.notifyDriver(tab(DocumentExpiryTab.EXPIRED).get(0).documentId(), "Mayowa");

        assertThat(expiry.tracker(null, null, DocumentExpirySort.DAYS_LEFT, Sort.Direction.DESC, 0, 20).content())
                .extracting(DocumentExpiryView::daysLeft).containsExactly(200L, 20L, 15L, 14L, 3L, 0L, -1L, -10L);
        assertThat(expiry.tracker(null, null, DocumentExpirySort.STATUS, Sort.Direction.ASC, 0, 20).content())
                .extracting(DocumentExpiryView::status).containsExactly(DocumentExpiryStatus.EXPIRED, DocumentExpiryStatus.EXPIRED,
                        DocumentExpiryStatus.EXPIRING_SOON, DocumentExpiryStatus.EXPIRING_SOON, DocumentExpiryStatus.EXPIRING_SOON,
                        DocumentExpiryStatus.VERIFIED, DocumentExpiryStatus.VERIFIED, DocumentExpiryStatus.NOT_VERIFIED);
        assertThat(expiry.tracker(null, null, DocumentExpirySort.LAST_NOTIFIED, Sort.Direction.DESC, 0, 20).content().get(0).lastNotifiedBy())
                .isEqualTo("Mayowa");
        assertThat(expiry.tracker(null, null, DocumentExpirySort.NAME, Sort.Direction.ASC, 0, 3).content())
                .extracting(DocumentExpiryView::firstName).containsExactly("Aisha", "Aisha", "Emeka");

        var page = expiry.tracker(null, null, null, null, 1, 3);
        assertThat(page.content()).hasSize(3);
        assertThat(page.totalElements()).isEqualTo(8);
    }

    // ---- ops reminders ---------------------------------------------------------------------------

    @Test
    void notifyingADriverSendsTheReminderAndRecordsWhoSentIt() {
        seed();
        DocumentExpiryView soon = tab(DocumentExpiryTab.EXPIRING_SOON).stream().filter(r -> r.daysLeft() == 3).findFirst().orElseThrow();

        DocumentExpiryView after = expiry.notifyDriver(soon.documentId(), "Mayowa Babatunde");

        assertThat(after.lastNotifiedBy()).isEqualTo("Mayowa Babatunde");
        assertThat(after.lastNotifiedAt()).isNotNull();
        assertThat(after.notifiedRecently()).isTrue();
        DocumentExpiringEvent sent = sentReminders().get(0);
        assertThat(sent.getDriverId()).isEqualTo(soon.driverId());
        assertThat(sent.getDaysRemaining()).isEqualTo(3);
        assertThat(sent.getExpiresOn()).isEqualTo(today.plusDays(3));
    }

    @Test
    void anExpiredDocumentsReminderSaysHowLongAgo() {
        seed();
        DocumentExpiryView expired = tab(DocumentExpiryTab.EXPIRED).get(0);

        expiry.notifyDriver(expired.documentId(), "Mayowa");

        assertThat(sentReminders().get(0).getDaysRemaining()).isEqualTo(-10);
    }

    @Test
    void aDriverCantBeRemindedAgainWithinTheCoolDown() {
        seed();
        String id = tab(DocumentExpiryTab.EXPIRING_SOON).get(0).documentId();
        expiry.notifyDriver(id, "Mayowa");

        assertThatThrownBy(() -> expiry.notifyDriver(id, "Mayowa"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("you can remind them again after");
    }

    @Test
    void onlyDocumentsDueSoonOrExpiredCanBeReminded() {
        seed();
        String verified = tab(DocumentExpiryTab.VERIFIED).get(0).documentId();

        assertThatThrownBy(() -> expiry.notifyDriver(verified, "Mayowa"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("within 14 days");
        assertThatThrownBy(() -> expiry.notifyDriver("nope", "Mayowa")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void notifyAllRemindsEveryDueDocumentInTheTabExceptThoseJustReminded() {
        seed();
        expiry.notifyDriver(tab(DocumentExpiryTab.EXPIRING_SOON).get(0).documentId(), "Mayowa");
        clearInvocations(kafkaTemplate);

        DocumentExpiryNotifyResult all = expiry.notifyAll(null, null, "Mayowa");

        assertThat(all).isEqualTo(new DocumentExpiryNotifyResult(4, 1));
        assertThat(sentReminders()).hasSize(4);
        assertThat(expiry.notifyAll(null, null, "Mayowa")).isEqualTo(new DocumentExpiryNotifyResult(0, 5));
        assertThat(expiry.notifyAll(DocumentExpiryTab.VERIFIED, null, "Mayowa")).isEqualTo(new DocumentExpiryNotifyResult(0, 0));
    }

    @Test
    void notifyAllFollowsTheTabAndSearch() {
        seed();

        assertThat(expiry.notifyAll(DocumentExpiryTab.EXPIRED, null, "Mayowa").notified()).isEqualTo(2);
        assertThat(expiry.notifyAll(DocumentExpiryTab.EXPIRING_SOON, "fatima", "Mayowa").notified()).isEqualTo(2);
        assertThat(expiry.notifyAll(DocumentExpiryTab.EXPIRING_SOON, null, "Mayowa").notified()).isEqualTo(1);
    }

    // ---- the nightly job -------------------------------------------------------------------------

    @Test
    void anExpiredDocumentStopsTheDriverPostingAndTellsThem() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "08053636321");
        approveAll("aaaa1111-1");
        expires("aaaa1111-1", DocumentType.INSURANCE, today.minusDays(1));
        clearInvocations(kafkaTemplate);

        assertThat(expiry.expireDue()).isEqualTo(1);

        DriverDocument doc = driverDocumentRepository.findByDriverIdAndDocumentType("aaaa1111-1", DocumentType.INSURANCE).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(VerificationStatus.EXPIRED);
        assertThat(doc.getRejectionReason()).contains("Insurance certificate");
        assertThat(doc.getLastNotifiedBy()).isEqualTo(DocumentExpiryService.SYSTEM);
        assertThat(vehicleRepository.findByDriverId("aaaa1111-1").get(0).getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(verification.overallStatus("aaaa1111-1")).isEqualTo(VerificationStatus.EXPIRED);
        assertThat(sentReminders().get(0).getDaysRemaining()).isEqualTo(-1);
        ArgumentCaptor<Object> rejected = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.DRIVER_REJECTED), rejected.capture());
        assertThat(((DriverRejectedEvent) rejected.getValue()).getDriverId()).isEqualTo("aaaa1111-1");

        clearInvocations(kafkaTemplate);
        assertThat(expiry.expireDue()).isZero();
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.DOCUMENT_EXPIRING), anyString(), any());
    }

    @Test
    void aDocumentOpsRejectedKeepsItsOwnReasonWhenItExpires() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "08053636321");
        decide("aaaa1111-1", new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.REJECTED, "Blurry"));
        expires("aaaa1111-1", DocumentType.INSURANCE, today.minusDays(3));

        assertThat(expiry.expireDue()).isZero();
        assertThat(driverDocumentRepository.findByDriverIdAndDocumentType("aaaa1111-1", DocumentType.INSURANCE).orElseThrow().getRejectionReason())
                .isEqualTo("Blurry");
    }

    @Test
    void renewingAnExpiredDocumentLetsTheDriverPostAgainWithoutAWelcome() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "08053636321");
        approveAll("aaaa1111-1");
        expires("aaaa1111-1", DocumentType.INSURANCE, today.minusDays(1));
        expiry.expireDue();
        clearInvocations(kafkaTemplate);

        driverDocumentService.create("aaaa1111-1", new CreateDriverDocumentRequest(DocumentType.INSURANCE, "https://img/new.jpg", "new-ins",
                today.plusYears(1), null));
        decide("aaaa1111-1", new ItemDecision(ReviewableItem.INSURANCE, VerificationStatus.APPROVED, null));

        ArgumentCaptor<Object> approved = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.DRIVER_APPROVED), approved.capture());
        assertThat(((DriverApprovedEvent) approved.getValue()).getFirstApproval()).isFalse();
        assertThat(verification.overallStatus("aaaa1111-1")).isEqualTo(VerificationStatus.APPROVED);
        DriverDocument renewed = driverDocumentRepository.findByDriverIdAndDocumentType("aaaa1111-1", DocumentType.INSURANCE).orElseThrow();
        assertThat(renewed.isReminderSent14d()).isFalse();
        assertThat(renewed.isReminderSent3d()).isFalse();
    }

    @Test
    void theFirstApprovalIsStillAWelcome() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "08053636321");
        clearInvocations(kafkaTemplate);

        approveAll("aaaa1111-1");

        ArgumentCaptor<Object> approved = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.DRIVER_APPROVED), approved.capture());
        assertThat(((DriverApprovedEvent) approved.getValue()).getFirstApproval()).isTrue();
    }

    @Test
    void remindersGoOnceEachWithTheDaysActuallyLeft() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "08053636321");
        approveAll("aaaa1111-1");
        expires("aaaa1111-1", DocumentType.INSURANCE, today.plusDays(2));
        expires("aaaa1111-1", DocumentType.ROADWORTHINESS_CERT, today.plusDays(10));
        clearInvocations(kafkaTemplate);

        assertThat(expiry.remindDue()).isEqualTo(2);
        assertThat(sentReminders()).extracting(DocumentExpiringEvent::getDaysRemaining).containsExactlyInAnyOrder(2, 10);
        DriverDocument insurance = driverDocumentRepository.findByDriverIdAndDocumentType("aaaa1111-1", DocumentType.INSURANCE).orElseThrow();
        assertThat(insurance.isReminderSent3d()).isTrue();
        assertThat(insurance.isReminderSent14d()).isTrue();
        assertThat(insurance.getLastNotifiedAt()).isNotNull();

        clearInvocations(kafkaTemplate);
        assertThat(expiry.remindDue()).isZero();

        expires("aaaa1111-1", DocumentType.ROADWORTHINESS_CERT, today.plusDays(3));
        assertThat(expiry.remindDue()).isEqualTo(1);
    }

    @Test
    void theJobReTellsTripServiceAboutEveryDriverStillHoldingAnExpiredDocument() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "08053636321");
        approveAll("aaaa1111-1");
        jdbcTemplate.update("update profile.driver_documents set status = 'EXPIRED' where driver_id = 'aaaa1111-1' and document_type = 'INSURANCE'");
        clearInvocations(kafkaTemplate);

        job.checkExpiringDocuments();

        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaTopics.DRIVER_REJECTED), any());
        assertThat(expiry.reassertBlocked()).isEqualTo(1);
    }

    @Test
    void aDocumentWithoutAnExpiryDateIsCountedButNotTracked() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "08053636321");
        jdbcTemplate.update("update profile.driver_documents set expires_on = null where driver_id = 'aaaa1111-1'");

        assertThat(expiry.counts(null)).isEqualTo(new DocumentExpiryCounts(0, 0, 0, 0, 0, 2));
        assertThat(tab(null)).isEmpty();
    }

    @Test
    void aDocumentWhoseDriverIsntIndexedYetStillShowsCountsAndGetsReminded() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "08053636321");
        expires("aaaa1111-1", DocumentType.INSURANCE, today.minusDays(2));
        driverIndexRepository.deleteAll();

        assertThat(tab(DocumentExpiryTab.EXPIRED)).singleElement().satisfies(r -> {
            assertThat(r.driverId()).isEqualTo("aaaa1111-1");
            assertThat(r.firstName()).isNull();
        });
        assertThat(expiry.counts(null).expired()).isEqualTo(1);
        assertThat(expiry.notifyAll(DocumentExpiryTab.EXPIRED, null, "Mayowa").notified()).isEqualTo(1);
        assertThat(search("emeka")).as("only name search needs the index").isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * Emeka: insurance expiring today, roadworthiness 10 days ago. Fatima: insurance in 3 days, roadworthiness in 14.
     * Aisha: insurance in 15 (approved), roadworthiness 200 (approved). Kingsley: insurance yesterday, roadworthiness in
     * 20 (still in review). Tunde: insurance with no expiry date.
     */
    private void seed() {
        submitAll("aaaa1111-1", "Emeka", "Okafor", "08053636321");
        submitAll("bbbb2222-2", "Fatima", "Bello", "08011112222");
        submitAll("cccc3333-3", "Aisha", "Lawal", "08099998888");
        approveAll("cccc3333-3");
        submitAll("dddd4444-4", "Kingsley", "Nwosu", "08077776666");
        expires("aaaa1111-1", DocumentType.INSURANCE, today);
        expires("aaaa1111-1", DocumentType.ROADWORTHINESS_CERT, today.minusDays(10));
        expires("bbbb2222-2", DocumentType.INSURANCE, today.plusDays(3));
        expires("bbbb2222-2", DocumentType.ROADWORTHINESS_CERT, today.plusDays(14));
        expires("cccc3333-3", DocumentType.INSURANCE, today.plusDays(15));
        expires("cccc3333-3", DocumentType.ROADWORTHINESS_CERT, today.plusDays(200));
        expires("dddd4444-4", DocumentType.INSURANCE, today.minusDays(1));
        expires("dddd4444-4", DocumentType.ROADWORTHINESS_CERT, today.plusDays(20));
        submitAll("eeee5555-5", "Tunde", "Abayomi", "08055554444");
        jdbcTemplate.update("update profile.driver_documents set expires_on = null where driver_id = 'eeee5555-5' and document_type = 'INSURANCE'");
        jdbcTemplate.update("delete from profile.driver_documents where driver_id = 'eeee5555-5' and document_type = 'ROADWORTHINESS_CERT'");
    }

    private List<DocumentExpiryView> tab(DocumentExpiryTab tab) {
        return expiry.tracker(tab, null, null, null, 0, 50).content();
    }

    private List<DocumentExpiryView> search(String term) {
        return expiry.tracker(null, term, null, null, 0, 50).content();
    }

    private List<DocumentExpiringEvent> sentReminders() {
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaTopics.DOCUMENT_EXPIRING), anyString(), sent.capture());
        return sent.getAllValues().stream().map(DocumentExpiringEvent.class::cast).toList();
    }

    private void expires(String driverId, DocumentType type, LocalDate on) {
        jdbcTemplate.update("update profile.driver_documents set expires_on = ?, reminder_sent_14d = false, reminder_sent_3d = false "
                + "where driver_id = ? and document_type = ?", on, driverId, type.name());
    }

    private void submitAll(String driverId, String first, String last, String phone) {
        phones.put(driverId, phone);
        driverProfileService.updateMine(driverId, new UpdateDriverProfileRequest(first, last, null, null, LocalDate.of(1990, 1, 1),
                "https://img/licence-" + driverId + ".jpg", "licence-" + driverId, "LAG12345678", null, null));
        vehicleService.register(driverId, driverId, new VehicleRegistrationRequest("Toyota", "Corolla", 2019, "Black", "LND-" + driverId.charAt(0), 4,
                "https://img/ext.jpg", "ext-1", "https://img/int.jpg", "int-1", true, true));
        driverDocumentService.create(driverId, new CreateDriverDocumentRequest(DocumentType.INSURANCE, "https://img/ins.jpg",
                "ins-" + driverId, today.plusYears(1), null));
        driverDocumentService.create(driverId, new CreateDriverDocumentRequest(DocumentType.ROADWORTHINESS_CERT, "https://img/rw.jpg",
                "rw-" + driverId, today.plusYears(1), null));
    }

    private final java.util.Map<String, String> phones = new java.util.concurrent.ConcurrentHashMap<>();

    private String phoneOf(String driverId) {
        return phones.get(driverId);
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
