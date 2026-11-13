package org.coryd.profile.service;

import org.coryd.common.enums.RiderDirectorySort;
import org.coryd.common.enums.RiderDirectoryStatus;
import org.coryd.common.enums.RiderIdentityStatus;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.events.RiderStatsChangedEvent;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.RiderIndexCounts;
import org.coryd.profile.dto.RiderIndexView;
import org.coryd.profile.dto.UpdateRiderProfileRequest;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.EmergencyContactRepository;
import org.coryd.profile.repository.RiderIndexRepository;
import org.coryd.profile.repository.RiderProfileRepository;
import org.coryd.profile.scheduler.RiderIndexJobs;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * rider_index: built from rider profiles and identity, given trip-service's numbers and identity's suspensions, and
 * queried in the database for the Riders Directory. Against a throwaway Postgres container.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({RiderProfileService.class, EmergencyContactService.class, RiderIndexService.class, RiderIndexer.class, RiderIndexJobs.class,
        RiderIdentityStatuses.class, SmileIdProperties.class})
class RiderIndexIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired RiderIndexService index;
    @Autowired RiderIndexJobs jobs;
    @Autowired RiderProfileService riderProfileService;
    @Autowired RiderIndexRepository riderIndexRepository;
    @Autowired RiderProfileRepository riderProfileRepository;
    @Autowired EmergencyContactRepository emergencyContactRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired IdentityVerificationRepository identityVerificationRepository;
    @Autowired SmileIdCheckRepository smileIdCheckRepository;

    @MockBean VerifiedIdentityService verifiedIdentityService;
    @MockBean IdentityServiceClient identityServiceClient;
    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    final Map<String, IdentityServiceClient.Contact> contacts = new ConcurrentHashMap<>();

    @BeforeEach
    void cleanSlate() {
        when(kafkaTemplate.send(anyString(), any())).thenReturn(new CompletableFuture<>());
        when(identityServiceClient.contacts(any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(contacts::get).filter(java.util.Objects::nonNull).toList();
        });
        contacts.clear();
        smileIdCheckRepository.deleteAll();
        identityVerificationRepository.deleteAll();
        emergencyContactRepository.deleteAll();
        riderProfileRepository.deleteAll();
        riderIndexRepository.deleteAll();
    }

    @Test
    void savingAProfileIndexesTheRiderWithTheirPhoneAndSignUpDate() {
        Instant joined = Instant.parse("2026-01-12T09:00:00Z");
        rider("aaaa1111-1", "Emeka", "Okafor", "08053636321", joined, null);

        RiderIndexView row = index.rider("aaaa1111-1");
        assertThat(row.firstName()).isEqualTo("Emeka");
        assertThat(row.phone()).isEqualTo("08053636321");
        assertThat(row.joinedAt()).isEqualTo(joined);
        assertThat(row.status()).isEqualTo(RiderDirectoryStatus.ACTIVE);
        assertThat(row.trips()).isNull();

        riderProfileService.updateMine("aaaa1111-1", new UpdateRiderProfileRequest("Emeka", "Okafor-Bello", null, null, null, null));
        assertThat(index.rider("aaaa1111-1").lastName()).isEqualTo("Okafor-Bello");
    }

    @Test
    void anAccountWithoutTheRiderRoleIsNotListed() {
        contacts.put("dddd4444-4", new IdentityServiceClient.Contact("dddd4444-4", "0801", null, null, null, Set.of("DRIVER")));
        riderProfileService.updateMine("dddd4444-4", new UpdateRiderProfileRequest("Driver", "Only", null, null, null, null));

        assertThat(riderIndexRepository.findById("dddd4444-4")).isEmpty();
        assertThat(jobs.backfill()).isZero();
    }

    @Test
    void tripNumbersLandOnTheRowAndAnOlderMessageIsIgnored() {
        rider("aaaa1111-1", "Emeka", "Okafor", "08053636321", null, null);
        Instant now = Instant.now();

        index.applyStats(stats("aaaa1111-1", 12, 4.8, 90, 2, "15700", now));
        index.applyStats(stats("aaaa1111-1", 3, 3.0, 50, 0, "100", now.minus(1, ChronoUnit.HOURS)));

        RiderIndexView row = index.rider("aaaa1111-1");
        assertThat(row.trips()).isEqualTo(12);
        assertThat(row.ratingAvg()).isEqualTo(4.8);
        assertThat(row.reliabilityPercent()).isEqualTo(90);
        assertThat(row.reportsFiled()).isEqualTo(2);
        assertThat(row.totalSpent()).isEqualByComparingTo("15700");
    }

    @Test
    void numbersForARiderNotYetIndexedBuildTheirRowFirst() {
        rider("aaaa1111-1", "Emeka", "Okafor", "08053636321", null, null);
        riderIndexRepository.deleteAll();

        index.applyStats(stats("aaaa1111-1", 4, 5.0, 100, 0, "4000", Instant.now()));

        assertThat(index.rider("aaaa1111-1").trips()).isEqualTo(4);
        index.applyStats(stats("nobody", 4, 5.0, 100, 0, "4000", Instant.now()));
        assertThat(riderIndexRepository.findById("nobody")).isEmpty();
    }

    @Test
    void suspendingAndReinstatingMovesTheRiderBetweenTabs() {
        seed();

        index.applySuspension("bbbb2222-2", Instant.now(), "Abusive to a driver");
        assertThat(index.counts(null)).isEqualTo(new RiderIndexCounts(4, 2, 2, 0, 0));
        RiderIndexView suspended = index.rider("bbbb2222-2");
        assertThat(suspended.status()).isEqualTo(RiderDirectoryStatus.SUSPENDED);
        assertThat(suspended.suspensionReason()).isEqualTo("Abusive to a driver");

        index.applySuspension("bbbb2222-2", null, null);
        assertThat(index.counts(null)).isEqualTo(new RiderIndexCounts(4, 3, 1, 0, 0));
        assertThat(index.rider("bbbb2222-2").suspensionReason()).isNull();
    }

    @Test
    void tabsSearchAndCountsAgree() {
        seed();

        assertThat(names(index.directory(RiderDirectoryStatus.SUSPENDED, null, null, null, null, 0, 20).content())).containsExactly("Aisha Lawal");
        assertThat(names(index.directory(null, null, "fat", null, null, 0, 20).content())).containsExactly("Fatima Bello");
        assertThat(names(index.directory(null, null, "R-CCCC33", null, null, 0, 20).content())).containsExactly("Kingsley Nwosu");
        assertThat(names(index.directory(null, null, "0801 111", null, null, 0, 20).content())).containsExactly("Fatima Bello");
        assertThat(names(index.directory(null, null, "50%_", null, null, 0, 20).content())).isEmpty();
        assertThat(index.counts("fat")).isEqualTo(new RiderIndexCounts(1, 1, 0, 0, 0));
    }

    @Test
    void sortingHappensInTheDatabaseWithBlanksLast() {
        seed();

        assertThat(names(index.directory(null, null, null, null, null, 0, 20).content()))
                .containsExactly("Kingsley Nwosu", "Aisha Lawal", "Fatima Bello", "Emeka Okafor");
        assertThat(names(index.directory(null, null, null, RiderDirectorySort.TRIPS, Sort.Direction.DESC, 0, 20).content()))
                .containsExactly("Fatima Bello", "Emeka Okafor", "Aisha Lawal", "Kingsley Nwosu");
        assertThat(names(index.directory(null, null, null, RiderDirectorySort.RETENTION, Sort.Direction.ASC, 0, 20).content()))
                .containsExactly("Fatima Bello", "Aisha Lawal", "Emeka Okafor", "Kingsley Nwosu");
        assertThat(names(index.directory(null, null, null, RiderDirectorySort.TOTAL_SPENT, Sort.Direction.DESC, 0, 20).content()).get(0))
                .isEqualTo("Fatima Bello");
        assertThat(names(index.directory(null, null, null, RiderDirectorySort.REPORTS, Sort.Direction.DESC, 0, 20).content()).get(0))
                .isEqualTo("Aisha Lawal");

        var page = index.directory(null, null, null, RiderDirectorySort.NAME, Sort.Direction.ASC, 1, 2);
        assertThat(names(page.content())).containsExactly("Fatima Bello", "Kingsley Nwosu");
        assertThat(page.totalElements()).isEqualTo(4);
    }

    @Test
    void theBackfillIndexesEveryRiderAndWalksPastAccountsThatArent() {
        contacts.put("0000-driver", new IdentityServiceClient.Contact("0000-driver", "0801", null, null, null, Set.of("DRIVER")));
        riderProfileRepository.save(RiderProfile.builder().userId("0000-driver").firstName("Not").lastName("Rider").build());
        for (int i = 0; i < 3; i++) {
            String id = "rider-" + i;
            contacts.put(id, new IdentityServiceClient.Contact(id, "0802" + i, null, null, null, Set.of("RIDER")));
            riderProfileRepository.save(RiderProfile.builder().userId(id).firstName("Rider").lastName(String.valueOf(i)).build());
        }
        riderIndexRepository.deleteAll();

        assertThat(jobs.backfill()).isEqualTo(3);
        assertThat(riderIndexRepository.count()).isEqualTo(3);
        assertThat(jobs.backfill()).isZero();
        assertThat(jobs.rebuildAll()).isEqualTo(3);
    }

    @Test
    void anUnknownRiderIsNotFound() {
        assertThatThrownBy(() -> index.rider("nope")).isInstanceOf(ResourceNotFoundException.class).hasMessage("Rider not found");
    }

    @Test
    void identityBeingDownStillIndexesTheRider() {
        riderProfileService.updateMine("aaaa1111-1", new UpdateRiderProfileRequest("Emeka", "Okafor", null, null, null, null));

        assertThat(index.rider("aaaa1111-1").phone()).isNull();
        contacts.put("aaaa1111-1", new IdentityServiceClient.Contact("aaaa1111-1", "08053636321", null, null, null, Set.of("RIDER")));
        index.refresh("aaaa1111-1");
        assertThat(index.rider("aaaa1111-1").phone()).isEqualTo("08053636321");
    }

    // ---- identity status -------------------------------------------------------------------------

    @Test
    void identityStatusFollowsTheRidersCheckAsItChanges() {
        rider("aaaa1111-1", "Emeka", "Okafor", "08053636321", null, null);
        assertThat(index.rider("aaaa1111-1").identityStatus()).as("never submitted").isEqualTo(RiderIdentityStatus.NOT_STARTED);

        IdentityVerification v = identityVerificationRepository.save(IdentityVerification.builder()
                .userId("aaaa1111-1").status(VerificationStatus.PENDING).submittedAt(Instant.now()).build());
        assertThat(identity("aaaa1111-1")).isEqualTo(RiderIdentityStatus.PENDING);

        v = setStatus(v, VerificationStatus.IN_REVIEW);
        assertThat(identity("aaaa1111-1")).isEqualTo(RiderIdentityStatus.IN_REVIEW);

        v = setStatus(v, VerificationStatus.REJECTED);
        assertThat(identity("aaaa1111-1")).isEqualTo(RiderIdentityStatus.FAILED);

        v = setStatus(v, VerificationStatus.PENDING);
        SmileIdCheck check = smileIdCheckRepository.save(SmileIdCheck.builder().userId("aaaa1111-1").checkType(SmileIdCheckType.NIN)
                .retryReason(SmileIdRetryReason.FACE_MISMATCH).attempts(1).build());
        assertThat(identity("aaaa1111-1")).as("Smile ID asked for a retry").isEqualTo(RiderIdentityStatus.FAILED);

        check.setAttempts(SmileIdCheck.MAX_ATTEMPTS);
        check = smileIdCheckRepository.save(check);
        assertThat(identity("aaaa1111-1")).as("every try used").isEqualTo(RiderIdentityStatus.LOCKED_OUT);

        setStatus(v, VerificationStatus.APPROVED);
        assertThat(identity("aaaa1111-1")).isEqualTo(RiderIdentityStatus.APPROVED);

        identityVerificationRepository.deleteAll();
        assertThat(identity("aaaa1111-1")).isEqualTo(RiderIdentityStatus.NOT_STARTED);
    }

    @Test
    void theNightlyRebuildPutsTheIdentityStatusRight() {
        seed();
        identityVerificationRepository.save(IdentityVerification.builder().userId("bbbb2222-2").status(VerificationStatus.APPROVED).build());
        jdbcTemplate.update("update profile.rider_index set identity_status = 'NOT_STARTED' where rider_id = 'bbbb2222-2'");
        assertThat(identity("bbbb2222-2")).isEqualTo(RiderIdentityStatus.NOT_STARTED);

        jobs.rebuildAll();

        assertThat(identity("bbbb2222-2")).isEqualTo(RiderIdentityStatus.APPROVED);
        assertThat(identity("aaaa1111-1")).isEqualTo(RiderIdentityStatus.NOT_STARTED);
    }

    @Test
    void theIdentityFilterAndTheVerifiedCount() {
        seed();
        identityVerificationRepository.save(IdentityVerification.builder().userId("bbbb2222-2").status(VerificationStatus.APPROVED).build());
        identityVerificationRepository.save(IdentityVerification.builder().userId("dddd4444-4").status(VerificationStatus.IN_REVIEW).build());

        assertThat(names(index.directory(null, RiderIdentityStatus.APPROVED, null, null, null, 0, 20).content()))
                .containsExactly("Fatima Bello");
        assertThat(names(index.directory(null, RiderIdentityStatus.IN_REVIEW, null, null, null, 0, 20).content()))
                .containsExactly("Aisha Lawal");
        assertThat(names(index.directory(null, RiderIdentityStatus.NOT_STARTED, null, null, null, 0, 20).content()))
                .containsExactly("Kingsley Nwosu", "Emeka Okafor");
        assertThat(index.directory(RiderDirectoryStatus.ACTIVE, RiderIdentityStatus.IN_REVIEW, null, null, null, 0, 20).content())
                .as("Aisha is suspended").isEmpty();
        assertThat(index.counts(null).verified()).isEqualTo(1);
        assertThat(index.counts("emeka").verified()).as("same search as the table").isZero();
    }

    // ---- last trip -------------------------------------------------------------------------------

    @Test
    void lastTripAtComesWithTheTripNumbers() {
        rider("aaaa1111-1", "Emeka", "Okafor", "08053636321", null, null);
        Instant now = Instant.now();
        Instant trip = now.minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

        index.applyStats(stats("aaaa1111-1", 12, trip, now));
        assertThat(index.rider("aaaa1111-1").lastTripAt()).isEqualTo(trip);

        index.applyStats(stats("aaaa1111-1", 11, now.minus(3, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS)));
        assertThat(index.rider("aaaa1111-1").lastTripAt()).as("an older message is ignored").isEqualTo(trip);

        index.applyStats(stats("aaaa1111-1", 12, 4.8, 90, 2, "15700", now.plusSeconds(1)));
        assertThat(index.rider("aaaa1111-1").lastTripAt()).as("a sender without lastTripAt leaves it alone").isEqualTo(trip);

        index.applyStats(stats("aaaa1111-1", 0, null, now.plusSeconds(2)));
        assertThat(index.rider("aaaa1111-1").lastTripAt()).as("no completed trips any more").isNull();
    }

    @Test
    void sortingByLastTripPutsRidersWhoNeverTravelledLastAndCountsTheActiveOnes() {
        seed();
        Instant now = Instant.now();
        index.applyStats(stats("aaaa1111-1", 213, now.minus(1, ChronoUnit.DAYS), now.plusSeconds(1)));
        index.applyStats(stats("bbbb2222-2", 283, now.minus(10, ChronoUnit.DAYS), now.plusSeconds(1)));
        index.applyStats(stats("dddd4444-4", 20, now.minus(6, ChronoUnit.DAYS), now.plusSeconds(1)));

        assertThat(names(index.directory(null, null, null, RiderDirectorySort.LAST_TRIP, Sort.Direction.DESC, 0, 20).content()))
                .containsExactly("Emeka Okafor", "Aisha Lawal", "Fatima Bello", "Kingsley Nwosu");
        assertThat(names(index.directory(null, null, null, RiderDirectorySort.LAST_TRIP, Sort.Direction.ASC, 0, 20).content()))
                .containsExactly("Fatima Bello", "Aisha Lawal", "Emeka Okafor", "Kingsley Nwosu");
        assertThat(index.counts(null)).isEqualTo(new RiderIndexCounts(4, 3, 1, 0, 2));
        assertThat(index.counts("fat").activeLast7Days()).isZero();
    }

    private RiderIdentityStatus identity(String riderId) {
        return index.rider(riderId).identityStatus();
    }

    private IdentityVerification setStatus(IdentityVerification v, VerificationStatus status) {
        v.setStatus(status);
        return identityVerificationRepository.save(v);
    }

    private static RiderStatsChangedEvent stats(String riderId, long trips, Instant lastTripAt, Instant at) {
        return RiderStatsChangedEvent.builder().riderId(riderId).trips(trips).ratingCount(0).reportsFiled(0)
                .totalSpent(BigDecimal.ZERO).lastTripAt(lastTripAt).computedAt(at).build();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Emeka (joined Jan), Fatima (Mar), Aisha (Apr, suspended), Kingsley (May, no trips yet). */
    private void seed() {
        rider("aaaa1111-1", "Emeka", "Okafor", "08053636321", Instant.parse("2026-01-12T09:00:00Z"), null);
        rider("bbbb2222-2", "Fatima", "Bello", "08011112222", Instant.parse("2026-03-01T09:00:00Z"), null);
        rider("dddd4444-4", "Aisha", "Lawal", "08099998888", Instant.parse("2026-04-01T09:00:00Z"), Instant.now());
        rider("cccc3333-3", "Kingsley", "Nwosu", "08077776666", Instant.parse("2026-05-01T09:00:00Z"), null);
        Instant now = Instant.now();
        index.applyStats(stats("aaaa1111-1", 213, 4.88, 91, 0, "157000", now));
        index.applyStats(stats("bbbb2222-2", 283, 4.5, 67, 1, "203000", now));
        index.applyStats(stats("dddd4444-4", 20, 4.9, 72, 3, "9000", now));
    }

    private void rider(String id, String first, String last, String phone, Instant joined, Instant suspendedAt) {
        contacts.put(id, new IdentityServiceClient.Contact(id, phone, suspendedAt, suspendedAt == null ? null : "Unsafe", joined, Set.of("RIDER")));
        riderProfileService.updateMine(id, new UpdateRiderProfileRequest(first, last, null, null, null, null));
    }

    private static RiderStatsChangedEvent stats(String riderId, long trips, double rating, int reliability, long reports, String spent, Instant at) {
        return new RiderStatsChangedEvent(riderId, trips, rating, 5, reliability, reports, new BigDecimal(spent), at);
    }

    private static List<String> names(List<RiderIndexView> rows) {
        return rows.stream().map(r -> r.firstName() + " " + r.lastName()).toList();
    }
}
