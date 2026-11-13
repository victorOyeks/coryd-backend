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
        // Each source only answers once all four have been asked - a one-after-another merge would never get there.
        CountDownLatch allAsked = new CountDownLatch(4);
        stub(OpsAuditClient.IDENTITY, allAsked, entry("i1", 1, OpsAuditCategory.ACCOUNT, "ACCOUNT"),
                entry("i2", 7, OpsAuditCategory.ACCOUNT, "ACCOUNT"));
        stub(OpsAuditClient.PAYMENT, allAsked, entry("p1", 3, OpsAuditCategory.MONEY, "BOOKING"));
        stub(OpsAuditClient.PROFILE, allAsked, entry("v1", 5, OpsAuditCategory.VERIFICATION, "VERIFICATION"));
        stub(OpsAuditClient.TRIP, allAsked, entry("t1", 2, OpsAuditCategory.TRIPS, "ROUTE"),
                entry("t2", 9, OpsAuditCategory.TRIPS, "ROUTE"));

        AuditFeed feed = service.page(new AuditFilter(null, null, null, null, null, null, null, null), null, 4);

        assertThat(feed.items()).extracting(AuditItem::id).containsExactly("IDENTITY:i1", "TRIP:t1", "PAYMENT:p1", "PROFILE:v1");
        assertThat(feed.hasMore()).isTrue();
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
