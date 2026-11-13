package org.coryd.trip.audit;

import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.repository.OpsAuditLogRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Trip, booking, SOS, report and rating actions in the unified audit feed. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import(TripAuditSource.class)
class TripAuditSourceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final Instant T0 = Instant.parse("2026-11-10T09:00:00Z");

    @Autowired TripAuditSource source;
    @Autowired OpsAuditLogRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM trip.ops_audit_log");
    }

    private void log(OpsAuditLog.Action action, OpsAuditLog.TargetType type, String target, String actor, String reason, Instant at) {
        OpsAuditLog saved = repository.save(OpsAuditLog.builder().actorId(actor).actorName("Admin " + actor).action(action).targetType(type)
                .targetId(target).routePostId(type == OpsAuditLog.TargetType.RATING ? null : "route-1").reason(reason)
                .beforeState("{\"status\":\"OPEN\"}").afterState("{\"status\":\"DONE\"}").build());
        jdbc.update("UPDATE trip.ops_audit_log SET created_at = ? WHERE id = ?", java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC), saved.getId());
    }

    private static OpsAuditQuery q(List<OpsAuditCategory> categories, List<String> types, List<String> actions, String target,
                                   Instant beforeAt, String beforeId, int size) {
        return new OpsAuditQuery(null, actions, categories, types, target, null, null, null, beforeAt, beforeId, size);
    }

    @Test
    void tripsAndSafetyComeNewestFirstPagedByCursorAndFiltered() {
        log(OpsAuditLog.Action.CANCEL_TRIP, OpsAuditLog.TargetType.ROUTE, "route-1", "a1", "Driver unwell", T0);
        log(OpsAuditLog.Action.CANCEL_BOOKING, OpsAuditLog.TargetType.BOOKING, "booking-1", "a1", "Rider asked", T0.plusSeconds(1));
        log(OpsAuditLog.Action.SOS_RESOLVED, OpsAuditLog.TargetType.SOS, "sos-1", "a2", "Rider safe", T0.plusSeconds(1));
        log(OpsAuditLog.Action.RATING_HIDDEN, OpsAuditLog.TargetType.RATING, "rating-1", "a2", "Abusive", T0.plusSeconds(2));
        log(OpsAuditLog.Action.REPORT_RESOLVED, OpsAuditLog.TargetType.ISSUE, "issue-1", "a1", "Refunded", T0.plusSeconds(3));

        List<OpsAuditEntry> all = new ArrayList<>();
        OpsAuditPage page = source.page(q(null, null, null, null, null, null, 2));
        all.addAll(page.items());
        while (page.hasMore()) {
            OpsAuditEntry last = page.items().getLast();
            page = source.page(q(null, null, null, null, last.at(), last.id(), 2));
            all.addAll(page.items());
        }
        assertThat(all).hasSize(5).extracting(OpsAuditEntry::id).doesNotHaveDuplicates();
        assertThat(all.getFirst().action()).isEqualTo("REPORT_RESOLVED");
        assertThat(all.getLast().action()).isEqualTo("CANCEL_TRIP");

        assertThat(source.page(q(List.of(OpsAuditCategory.SAFETY), null, null, null, null, null, 50)).items())
                .extracting(OpsAuditEntry::targetType).containsExactly("ISSUE", "RATING", "SOS");
        assertThat(source.page(q(List.of(OpsAuditCategory.TRIPS), null, null, null, null, null, 50)).items())
                .extracting(OpsAuditEntry::category).containsOnly(OpsAuditCategory.TRIPS).hasSize(2);
        assertThat(source.page(q(null, List.of("BOOKING"), null, null, null, null, 50)).items()).extracting(OpsAuditEntry::targetId)
                .containsExactly("booking-1");
        assertThat(source.page(q(List.of(OpsAuditCategory.SAFETY), List.of("ROUTE"), null, null, null, null, 50)).items()).isEmpty();
        assertThat(source.page(q(null, null, List.of("CANCEL_TRIP", "NOT_AN_ACTION"), null, null, null, 50)).items()).hasSize(1);
        assertThat(source.page(q(null, null, List.of("NOT_AN_ACTION"), null, null, null, 50)).items()).isEmpty();
        assertThat(source.page(q(null, null, null, "sos-1", null, null, 50)).items().getFirst().relatedId()).isEqualTo("route-1");
        assertThat(source.page(new OpsAuditQuery("a2", null, null, null, null, null, null, "abus", null, null, 50)).items())
                .extracting(OpsAuditEntry::targetId).containsExactly("rating-1");
        assertThat(source.page(q(List.of(OpsAuditCategory.MONEY), null, null, null, null, null, 50)).items()).isEmpty();
    }
}
