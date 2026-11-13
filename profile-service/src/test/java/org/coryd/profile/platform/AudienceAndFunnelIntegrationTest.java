package org.coryd.profile.platform;

import org.coryd.common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Broadcast audiences and the verification funnel, from the directory indexes. Suspended accounts are never in an audience. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({AudienceService.class, VerificationFunnelService.class})
class AudienceAndFunnelIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String R1 = "0de4b2aa-0000-0000-0000-000000000001";
    static final String R2 = "1a2b3c00-0000-0000-0000-000000000002";
    static final String R_SUSPENDED = "2b3c4d00-0000-0000-0000-000000000003";
    static final String D1 = "7c34f100-0000-0000-0000-000000000004";
    static final String D2 = "8d45a200-0000-0000-0000-000000000005";

    @Autowired AudienceService audience;
    @Autowired VerificationFunnelService funnel;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("delete from profile.rider_index");
        jdbc.update("delete from profile.driver_index");
        jdbc.update("delete from profile.rider_profiles");
        jdbc.update("delete from profile.driver_profiles");
        Instant now = Instant.now();
        rider(R1, "08011110001", "ACTIVE", "APPROVED", now.minus(3, ChronoUnit.DAYS), "amaka@example.com");
        rider(R2, "08011110002", "ACTIVE", "NOT_STARTED", null, null);
        rider(R_SUSPENDED, "08011110003", "SUSPENDED", "APPROVED", now.minus(1, ChronoUnit.DAYS), null);
        driver(D1, "08022220001", "VERIFIED", "APPROVED", now.minus(1, ChronoUnit.DAYS), "PENDING");
        driver(D2, null, "PENDING", "IN_REVIEW", null, "IN_REVIEW");
    }

    @Test
    void eachAudienceTypeResolvesToTheRightPeopleWithContacts() {
        assertThat(audience.count(AudienceService.Type.ALL_RIDERS, null, null, null).total()).as("suspended left out").isEqualTo(2);
        assertThat(audience.count(AudienceService.Type.ALL_DRIVERS, null, null, null).total()).isEqualTo(2);
        AudienceService.Count verified = audience.count(AudienceService.Type.VERIFIED_DRIVERS, null, null, null);
        assertThat(verified.total()).isEqualTo(1);
        assertThat(verified.withPhone()).isEqualTo(1);
        AudienceService.Count active = audience.count(AudienceService.Type.ACTIVE_RIDERS, 7, null, null);
        assertThat(active.total()).isEqualTo(1);
        assertThat(active.withEmail()).isEqualTo(1);
        assertThat(audience.count(AudienceService.Type.ACTIVE_RIDERS, 2, null, null).total()).isZero();

        AudienceService.Page page = audience.page(AudienceService.Type.ALL_RIDERS, null, null, null, null, 10);
        assertThat(page.members()).extracting(AudienceService.Member::userId).containsExactly(R1, R2);
        assertThat(page.members().get(0).ref()).isEqualTo("R-0DE4B2");
        assertThat(page.members().get(0).email()).isEqualTo("amaka@example.com");
        assertThat(page.nextAfter()).isNull();
    }

    @Test
    void aListedAudienceTakesIdsAndReferencesAndSaysWhoWasntFound() {
        AudienceService.Count count = audience.count(AudienceService.Type.USERS, null, List.of(D2, "nobody"), List.of("R-0DE4B2", "R-2B3C4D"));
        assertThat(count.total()).isEqualTo(2);
        assertThat(count.unmatched()).containsExactlyInAnyOrder("nobody", "R-2B3C4D");
        assertThatThrownBy(() -> audience.count(AudienceService.Type.USERS, null, null, List.of("R-XYZ"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> audience.count(AudienceService.Type.USERS, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> audience.count(AudienceService.Type.ACTIVE_RIDERS, 0, null, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void largeAudiencesArePagedByUserId() {
        for (int i = 0; i < 25; i++) {
            rider(String.format("f%07d-0000-0000-0000-000000000000", i), "080" + i, "ACTIVE", "NOT_STARTED", null, null);
        }
        List<String> seen = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            AudienceService.Page page = audience.page(AudienceService.Type.ALL_RIDERS, null, null, null, after, 10);
            page.members().forEach(m -> seen.add(m.userId()));
            after = page.nextAfter();
            pages++;
        } while (after != null);
        assertThat(seen).hasSize(27).doesNotHaveDuplicates();
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void theFunnelAndPipelineCountTheSeededPeople() {
        VerificationFunnelService.Funnel f = funnel.funnel(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(f.stages()).filteredOn(s -> s.audience().equals("DRIVERS"))
                .extracting(VerificationFunnelService.Stage::stage, VerificationFunnelService.Stage::people)
                .contains(org.assertj.core.groups.Tuple.tuple("JOINED", 2L), org.assertj.core.groups.Tuple.tuple("SUBMITTED_DOCUMENTS", 2L),
                        org.assertj.core.groups.Tuple.tuple("APPROVED", 1L), org.assertj.core.groups.Tuple.tuple("COMPLETED_A_TRIP", 1L));
        assertThat(f.stages()).filteredOn(s -> s.audience().equals("RIDERS") && s.stage().equals("ID_APPROVED"))
                .singleElement().satisfies(s -> {
                    assertThat(s.people()).isEqualTo(2);
                    assertThat(s.percentOfJoined()).isEqualTo(66.7);
                });
        VerificationFunnelService.Pipeline p = funnel.pipeline();
        assertThat(p.driversTotal()).isEqualTo(2);
        assertThat(p.driversVerified()).isEqualTo(1);
        assertThat(p.driverQueue()).containsEntry("IN_REVIEW", 1L).containsEntry("PENDING", 1L);
        assertThat(p.ridersTotal()).isEqualTo(3);
        assertThat(p.ridersSuspended()).isEqualTo(1);
        assertThat(p.ridersActiveLast7Days()).isEqualTo(2);
        assertThat(p.riderIdentity()).containsEntry("APPROVED", 2L).containsEntry("NOT_STARTED", 1L);
    }

    private void rider(String id, String phone, String status, String identity, Instant lastTrip, String email) {
        jdbc.update("insert into profile.rider_index (rider_id, ref_key, first_name, last_name, phone, joined_at, directory_status,"
                        + " identity_status, last_trip_at) values (?, ?, 'Amaka', 'Obi', ?, now(), ?, ?, ?)",
                id, id.replace("-", ""), phone, status, identity, lastTrip == null ? null : Timestamp.from(lastTrip));
        jdbc.update("insert into profile.rider_profiles (id, user_id, email, created_at, updated_at) values (?, ?, ?, now(), now())",
                UUID.randomUUID().toString(), id, email);
    }

    private void driver(String id, String phone, String status, String overall, Instant lastTrip, String queueTab) {
        jdbc.update("insert into profile.driver_index (driver_id, ref_key, first_name, last_name, phone, joined_at, overall_status,"
                        + " directory_status, last_trip_at, queue_tab, submitted_at) values (?, ?, 'Emeka', 'Okafor', ?, now(), ?, ?, ?, ?, now())",
                id, id.replace("-", ""), phone, overall, status, lastTrip == null ? null : Timestamp.from(lastTrip), queueTab);
    }
}
