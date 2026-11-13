package org.coryd.payment.ops.audit;

import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.repository.OpsMoneyAuditRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Money actions in the unified audit feed. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import(PaymentAuditSource.class)
class PaymentAuditSourceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant T0 = Instant.parse("2026-11-10T09:00:00Z");

    @Autowired PaymentAuditSource source;
    @Autowired OpsMoneyAuditRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment.ops_money_audit");
    }

    private void log(OpsMoneyAudit.Action action, OpsMoneyAudit.TargetType type, String target, String request, Instant at) {
        OpsMoneyAudit saved = repository.save(OpsMoneyAudit.builder().actorId("fin-1").actorName("Fola Finance").action(action)
                .targetType(type).targetId(target).requestId(request).amount(new BigDecimal("2500.00")).reason("Double charge").build());
        jdbc.update("UPDATE payment.ops_money_audit SET created_at = ? WHERE id = ?", java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC), saved.getId());
    }

    @Test
    void moneyActionsPageNewestFirstAndARequestIsItsOwnTargetType() {
        log(OpsMoneyAudit.Action.REFUND_REQUESTED, OpsMoneyAudit.TargetType.BOOKING, "booking-1", "req-1", T0);
        log(OpsMoneyAudit.Action.REQUEST_APPROVED, OpsMoneyAudit.TargetType.REQUEST, "req-1", "req-1", T0.plusSeconds(5));
        log(OpsMoneyAudit.Action.REFUND_EXECUTED, OpsMoneyAudit.TargetType.BOOKING, "booking-1", "req-1", T0.plusSeconds(6));
        log(OpsMoneyAudit.Action.PAYOUT_ACCOUNT_REVEALED, OpsMoneyAudit.TargetType.PAYOUT_ACCOUNT, "driver-1", null, T0.plusSeconds(7));

        OpsAuditPage first = source.page(new OpsAuditQuery(null, null, null, null, null, null, null, null, null, null, 3));
        assertThat(first.items()).extracting(OpsAuditEntry::action)
                .containsExactly("PAYOUT_ACCOUNT_REVEALED", "REFUND_EXECUTED", "REQUEST_APPROVED");
        assertThat(first.hasMore()).isTrue();
        OpsAuditEntry last = first.items().getLast();
        assertThat(last.targetType()).isEqualTo("MONEY_REQUEST");
        OpsAuditPage second = source.page(new OpsAuditQuery(null, null, null, null, null, null, null, null, last.at(), last.id(), 3));
        assertThat(second.items()).extracting(OpsAuditEntry::action).containsExactly("REFUND_REQUESTED");
        assertThat(second.hasMore()).isFalse();
        assertThat(second.items().getFirst().amount()).isEqualByComparingTo("2500");
        assertThat(second.items().getFirst().category()).isEqualTo(OpsAuditCategory.MONEY);

        assertThat(source.page(new OpsAuditQuery(null, null, null, List.of("MONEY_REQUEST"), null, null, null, null, null, null, 50)).items())
                .hasSize(1);
        assertThat(source.page(new OpsAuditQuery(null, null, null, null, "req-1", null, null, null, null, null, 50)).items())
                .as("a request id finds every action on it").hasSize(3);
        assertThat(source.page(new OpsAuditQuery(null, List.of("REFUND_EXECUTED"), null, null, null, null, null, null, null, null, 50)).items())
                .hasSize(1);
        assertThat(source.page(new OpsAuditQuery(null, null, List.of(OpsAuditCategory.TRIPS), null, null, null, null, null, null, null, 50))
                .items()).isEmpty();
        assertThat(source.page(new OpsAuditQuery("someone-else", null, null, null, null, null, null, null, null, null, 50)).items()).isEmpty();
    }
}
