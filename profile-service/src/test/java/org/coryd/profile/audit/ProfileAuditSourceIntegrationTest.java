package org.coryd.profile.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.VerificationDecisionLog;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
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

/** Verification decisions in the unified audit feed: newest first, keyset paging, filters, what's left out. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import(ProfileAuditSource.class)
class ProfileAuditSourceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant T0 = Instant.parse("2026-11-10T09:00:00Z");

    @Autowired ProfileAuditSource source;
    @Autowired VerificationDecisionLogRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM profile.verification_decision_log");
    }

    private VerificationDecisionLog log(String person, DecisionSubject item, VerificationDecisionAction action, VerificationStatus status,
                                        String by, String note, Instant at) {
        VerificationDecisionLog saved = repository.save(VerificationDecisionLog.builder().driverId(person).item(item).action(action)
                .previousStatus(VerificationStatus.IN_REVIEW).status(status).decidedBy(by == null ? "Smile ID" : "Ada Ops")
                .decidedById(by).note(note).build());
        jdbc.update("UPDATE profile.verification_decision_log SET created_at = ? WHERE id = ?", java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC), saved.getId());
        return saved;
    }

    private static OpsAuditQuery q(String actor, List<String> actions, String target, String search, Instant beforeAt, String beforeId,
                                   int size) {
        return new OpsAuditQuery(actor, actions, null, null, target, null, null, search, beforeAt, beforeId, size);
    }

    @Test
    void pagesNewestFirstThroughTiesWithTheKeysetCursor() {
        for (int i = 0; i < 5; i++) {
            // two at each instant: ties are broken by id, the same way on every page
            log("p-" + i, DecisionSubject.INSURANCE, VerificationDecisionAction.APPROVED, VerificationStatus.APPROVED, "ops-1",
                    "ok " + i, T0.plusSeconds(i / 2));
        }
        List<OpsAuditEntry> all = new ArrayList<>();
        OpsAuditPage page = source.page(q(null, null, null, null, null, null, 2));
        all.addAll(page.items());
        while (page.hasMore()) {
            OpsAuditEntry last = page.items().getLast();
            page = source.page(q(null, null, null, null, last.at(), last.id(), 2));
            all.addAll(page.items());
        }
        assertThat(all).hasSize(5);
        assertThat(all).extracting(OpsAuditEntry::id).doesNotHaveDuplicates();
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).at()).isBeforeOrEqualTo(all.get(i - 1).at());
        }
        OpsAuditEntry e = all.getFirst();
        assertThat(e.category()).isEqualTo(OpsAuditCategory.VERIFICATION);
        assertThat(e.targetType()).isEqualTo("VERIFICATION");
        assertThat(e.subject()).isEqualTo("Insurance certificate");
        assertThat(e.source()).isEqualTo("PROFILE");
    }

    @Test
    void filtersAndLeavesOutSmileIdResultsAndResetsRecordedInTheAccountHistory() throws Exception {
        log("p-1", DecisionSubject.IDENTITY, VerificationDecisionAction.OVERRIDE_APPROVED, VerificationStatus.APPROVED, "ops-1",
                "Face matches the licence", T0);
        log("p-2", DecisionSubject.GOVERNMENT_ID, VerificationDecisionAction.REJECTED, VerificationStatus.REJECTED, "ops-2", "Blurry", T0.plusSeconds(1));
        log("p-2", DecisionSubject.GOVERNMENT_ID, VerificationDecisionAction.PROVIDER_RESULT, VerificationStatus.REJECTED, null, "late", T0.plusSeconds(2));
        log("p-2", DecisionSubject.GOVERNMENT_ID, VerificationDecisionAction.ATTEMPTS_RESET, VerificationStatus.REJECTED, "ops-2", "reset", T0.plusSeconds(3));
        log("p-3", DecisionSubject.VEHICLE, null, VerificationStatus.APPROVED, "ops-1", "legacy", T0.plusSeconds(4));

        assertThat(source.page(q(null, null, null, null, null, null, 50)).items()).extracting(OpsAuditEntry::action)
                .containsExactly("APPROVED", "REJECTED", "OVERRIDE_APPROVED");
        assertThat(source.page(q("ops-2", null, null, null, null, null, 50)).items()).extracting(OpsAuditEntry::targetId).containsExactly("p-2");
        assertThat(source.page(q(null, null, "p-1", null, null, null, 50)).items()).hasSize(1);
        assertThat(source.page(q(null, List.of("APPROVED"), null, null, null, null, 50)).items()).extracting(OpsAuditEntry::targetId)
                .as("a legacy entry matches by its status").containsExactly("p-3");
        assertThat(source.page(q(null, List.of("OVERRIDE_APPROVED", "REJECTED"), null, null, null, null, 50)).items()).hasSize(2);
        assertThat(source.page(q(null, List.of("PROVIDER_RESULT"), null, null, null, null, 50)).items()).isEmpty();
        assertThat(source.page(q(null, null, null, "FACE", null, null, 50)).items()).extracting(OpsAuditEntry::targetId).containsExactly("p-1");
        assertThat(source.page(new OpsAuditQuery(null, null, null, null, null, T0.plusSeconds(1), T0.plusSeconds(4), null, null, null, 50))
                .items()).extracting(OpsAuditEntry::action).containsExactly("REJECTED");
        assertThat(source.page(new OpsAuditQuery(null, null, List.of(OpsAuditCategory.MONEY), null, null, null, null, null, null, null, 50))
                .items()).isEmpty();

        OpsAuditEntry override = source.page(q(null, List.of("OVERRIDE_APPROVED"), null, null, null, null, 50)).items().getFirst();
        assertThat(objectMapper.readTree(override.before()).get("status").asText()).isEqualTo("IN_REVIEW");
        assertThat(objectMapper.readTree(override.after()).get("status").asText()).isEqualTo("APPROVED");
        assertThat(override.actorName()).isEqualTo("Ada Ops");
    }
}
