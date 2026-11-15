package org.coryd.ops.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.ops.client.DownstreamException;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.OpsAuditClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsAuditServiceMergeTest {

    private final OpsAuditClient auditClient = mock(OpsAuditClient.class);
    private final IdentityServiceClient identity = mock(IdentityServiceClient.class);
    private final OpsAuditService service = new OpsAuditService(auditClient, identity, new ObjectMapper().registerModule(new JavaTimeModule()));

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

    private static OpsAuditEntry entry(String id, int minutesAgo, OpsAuditCategory category, String targetType) {
        return new OpsAuditEntry(id, T0.minusSeconds(minutesAgo * 60L), null, category, "ACTION", "admin-1", "Ada Ops", targetType,
                "target-" + id, null, "why", null, null, null, null);
    }

    @Test
    void theSourcesAreAskedAtOnceAndMergedNewestFirst() {
        // Each source only answers once all five have been asked - a one-after-another merge would never get there.
        CountDownLatch allAsked = new CountDownLatch(5);
        stub(OpsAuditClient.IDENTITY, allAsked, entry("i1", 1, OpsAuditCategory.ACCOUNT, "ACCOUNT"),
                entry("i2", 7, OpsAuditCategory.ACCOUNT, "ACCOUNT"));
        stub(OpsAuditClient.PAYMENT, allAsked, entry("p1", 3, OpsAuditCategory.MONEY, "BOOKING"));
        stub(OpsAuditClient.PROFILE, allAsked, entry("v1", 5, OpsAuditCategory.VERIFICATION, "VERIFICATION"));
        stub(OpsAuditClient.TRIP, allAsked, entry("t1", 2, OpsAuditCategory.TRIPS, "ROUTE"),
                entry("t2", 9, OpsAuditCategory.TRIPS, "ROUTE"));
        stub(OpsAuditClient.NOTIFICATION, allAsked, entry("n1", 4, OpsAuditCategory.NOTIFICATIONS, "NOTIFICATION_JOB"));

        AuditFeed feed = service.page(new AuditFilter(null, null, null, null, null, null, null, null), null, 5);

        assertThat(feed.items()).extracting(AuditItem::id).containsExactly("IDENTITY:i1", "TRIP:t1", "PAYMENT:p1", "NOTIFICATION:n1",
                "PROFILE:v1");
        assertThat(feed.hasMore()).isTrue();
    }

    /** A source that pages its list by the cursor it is given, as the real ones do (newest first, keyset). */
    private void serve(String source, List<OpsAuditEntry> rows) {
        when(auditClient.page(eq(source), any())).thenAnswer(inv -> {
            org.coryd.common.audit.OpsAuditQuery q = inv.getArgument(1);
            List<OpsAuditEntry> after = rows.stream().filter(e -> q.beforeAt() == null || e.at().isBefore(q.beforeAt())
                    || (e.at().equals(q.beforeAt()) && e.id().compareTo(q.beforeId()) < 0)).toList();
            List<OpsAuditEntry> page = after.subList(0, Math.min(q.limit(), after.size()));
            return new OpsAuditPage(page, after.size() > page.size());
        });
    }

    @Test
    void notificationEntriesInterleaveByTimeAndThePagesHaveNoGapsOrRepeats() {
        serve(OpsAuditClient.IDENTITY, List.of(entry("i1", 1, OpsAuditCategory.ACCOUNT, "ACCOUNT"), entry("i2", 6, OpsAuditCategory.ACCOUNT, "ACCOUNT")));
        serve(OpsAuditClient.PAYMENT, List.of());
        serve(OpsAuditClient.PROFILE, List.of(entry("v1", 4, OpsAuditCategory.VERIFICATION, "VERIFICATION")));
        serve(OpsAuditClient.TRIP, List.of(entry("t1", 3, OpsAuditCategory.TRIPS, "ROUTE")));
        serve(OpsAuditClient.NOTIFICATION, List.of(entry("n1", 2, OpsAuditCategory.NOTIFICATIONS, "NOTIFICATION_JOB"),
                entry("n2", 5, OpsAuditCategory.NOTIFICATIONS, "BROADCAST"), entry("n3", 7, OpsAuditCategory.NOTIFICATIONS, "REPORT")));

        List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            AuditFeed feed = service.page(new AuditFilter(null, null, null, null, null, null, null, null), cursor, 2);
            feed.items().forEach(i -> seen.add(i.id()));
            cursor = feed.nextCursor();
            pages++;
        } while (cursor != null);
        assertThat(seen).containsExactly("IDENTITY:i1", "NOTIFICATION:n1", "TRIP:t1", "PROFILE:v1", "NOTIFICATION:n2", "IDENTITY:i2",
                "NOTIFICATION:n3");
        assertThat(pages).isEqualTo(4);
    }

    @Test
    void theNotificationsCategoryOnlyAsksNotificationService() {
        serve(OpsAuditClient.NOTIFICATION, List.of(entry("n1", 2, OpsAuditCategory.NOTIFICATIONS, "NOTIFICATION_JOB")));
        AuditFeed feed = service.page(new AuditFilter(null, null, List.of(OpsAuditCategory.NOTIFICATIONS), null, null, null, null, null), null, 10);
        assertThat(feed.items()).extracting(AuditItem::id).containsExactly("NOTIFICATION:n1");
        org.mockito.Mockito.verify(auditClient, org.mockito.Mockito.never()).page(eq(OpsAuditClient.TRIP), any());
        AuditFeed byType = service.page(new AuditFilter(null, null, null, List.of("NOTIFICATION_JOB"), null, null, null, null), null, 10);
        assertThat(byType.items()).hasSize(1);
        assertThat(OpsAuditService.TARGET_TYPES).contains("BROADCAST", "REPORT", "NOTIFICATION_JOB");
    }

    @Test
    void aSourceThatRefusesStillFailsThePageWithItsOwnStatus() {
        when(auditClient.page(any(), any())).thenReturn(new OpsAuditPage(List.of(), false));
        when(auditClient.page(eq(OpsAuditClient.PAYMENT), any()))
                .thenThrow(new DownstreamException(HttpStatus.FORBIDDEN, "Not allowed"));

        assertThatThrownBy(() -> service.page(new AuditFilter(null, null, null, null, null, null, null, null), null, 10))
                .isInstanceOf(DownstreamException.class).hasMessage("Not allowed");
    }

    private void stub(String source, CountDownLatch allAsked, OpsAuditEntry... entries) {
        when(auditClient.page(eq(source), any())).thenAnswer(inv -> {
            allAsked.countDown();
            assertThat(allAsked.await(5, TimeUnit.SECONDS)).as("all sources asked at once").isTrue();
            return new OpsAuditPage(List.of(entries), false);
        });
    }
}
