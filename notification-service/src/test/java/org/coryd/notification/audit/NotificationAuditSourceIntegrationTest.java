package org.coryd.notification.audit;

import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.notification.broadcast.NotificationOpsAudit;
import org.coryd.notification.broadcast.NotificationOpsAuditRepository;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * notification-service's part of the unified audit feed against its own migrations: newest first by (created_at, id),
 * the keyset cursor pages without gaps or repeats (including rows sharing an instant), and the filters.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import(NotificationAuditSource.class)
class NotificationAuditSourceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Instant T0 = Instant.parse("2026-11-10T09:00:00Z");

    @Autowired NotificationAuditSource source;
    @Autowired NotificationOpsAuditRepository audit;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        audit.deleteAll();
        row("BROADCAST_CREATED", "BROADCAST", "b-1", "admin-1", "Ada Ops", "Road closure", T0);
        row("REPORT_SENT", "REPORT", "DAILY", "admin-2", "Sam Boss", "Board asked", T0.plusSeconds(60));
        row("ONBOARDING_REMINDERS_SENT", "NOTIFICATION_JOB", "onboarding-reminders", "system", "Scheduled job", "Daily onboarding reminders",
                T0.plusSeconds(120));
        row("VERIFICATION_DIGEST_SENT", "NOTIFICATION_JOB", "verification-digest", "system", "Scheduled job", "Daily verification digest",
                T0.plusSeconds(120));
        row("BROADCAST_CANCELLED", "BROADCAST", "b-1", "admin-1", "Ada Ops", "Wrong date", T0.plusSeconds(180));
    }

    private void row(String action, String type, String target, String actorId, String actorName, String reason, Instant at) {
        NotificationOpsAudit saved = audit.save(NotificationOpsAudit.builder().action(action).targetType(type).targetId(target).actorId(actorId)
                .actorName(actorName).reason(reason).afterState("{\"n\":1}").build());
        jdbc.update("UPDATE notification.ops_audit_log SET created_at = ? WHERE id = ?", LocalDateTime.ofInstant(at, ZoneOffset.UTC), saved.getId());
    }

    private static OpsAuditQuery query(Instant beforeAt, String beforeId, int size) {
        return new OpsAuditQuery(null, null, null, null, null, null, null, null, beforeAt, beforeId, size);
    }

    @Test
    void newestFirstAndTheCursorPagesWithoutGapsOrRepeats() {
        OpsAuditPage all = source.page(query(null, null, 50));
        assertThat(all.items()).extracting(OpsAuditEntry::action).first().isEqualTo("BROADCAST_CANCELLED");
        assertThat(all.items()).extracting(OpsAuditEntry::action).last().isEqualTo("BROADCAST_CREATED");
        assertThat(all.items()).allSatisfy(e -> {
            assertThat(e.category()).isEqualTo(OpsAuditCategory.NOTIFICATIONS);
            assertThat(e.source()).isEqualTo("NOTIFICATION");
        });
        assertThat(all.items().get(0).at()).isEqualTo(T0.plusSeconds(180));
        assertThat(all.hasMore()).isFalse();

        List<String> paged = new ArrayList<>();
        Instant at = null;
        String id = null;
        boolean more = true;
        while (more) {
            OpsAuditPage page = source.page(query(at, id, 2));
            page.items().forEach(e -> paged.add(e.id()));
            if (!page.items().isEmpty()) {
                at = page.items().get(page.items().size() - 1).at();
                id = page.items().get(page.items().size() - 1).id();
            }
            more = page.hasMore();
        }
        assertThat(paged).as("two rows share an instant - the id breaks the tie")
                .containsExactlyElementsOf(all.items().stream().map(OpsAuditEntry::id).toList());
    }

    @Test
    void filtersByCategoryTargetTypeActionActorTargetAndSearch() {
        assertThat(source.page(new OpsAuditQuery(null, null, List.of(OpsAuditCategory.MONEY), null, null, null, null, null, null, null, 50))
                .items()).isEmpty();
        assertThat(source.page(new OpsAuditQuery(null, null, List.of(OpsAuditCategory.NOTIFICATIONS), List.of("NOTIFICATION_JOB"), null,
                null, null, null, null, null, 50)).items()).extracting(OpsAuditEntry::action)
                .containsExactlyInAnyOrder("ONBOARDING_REMINDERS_SENT", "VERIFICATION_DIGEST_SENT");
        assertThat(source.page(new OpsAuditQuery(null, null, null, List.of("ROUTE"), null, null, null, null, null, null, 50)).items()).isEmpty();
        assertThat(source.page(new OpsAuditQuery(null, List.of("REPORT_SENT"), null, null, null, null, null, null, null, null, 50)).items())
                .extracting(OpsAuditEntry::actorName).containsExactly("Sam Boss");
        assertThat(source.page(new OpsAuditQuery("admin-1", null, null, null, "b-1", null, null, null, null, null, 50)).items()).hasSize(2);
        assertThat(source.page(new OpsAuditQuery(null, null, null, null, null, T0.plusSeconds(100), T0.plusSeconds(150), null, null, null, 50))
                .items()).hasSize(2);
        assertThat(source.page(new OpsAuditQuery(null, null, null, null, null, null, null, "WRONG", null, null, 50)).items())
                .extracting(OpsAuditEntry::action).containsExactly("BROADCAST_CANCELLED");
    }
}
