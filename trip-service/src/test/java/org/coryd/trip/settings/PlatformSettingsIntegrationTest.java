package org.coryd.trip.settings;

import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.coryd.trip.config.FareProperties;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.config.SosOpsProperties;
import org.coryd.trip.config.StuckTripProperties;
import org.coryd.trip.config.policy.CancellationPolicy;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.PlatformSettingChange;
import org.coryd.trip.repository.OpsAuditLogRepository;
import org.coryd.trip.repository.PlatformSettingChangeRepository;
import org.coryd.trip.repository.PlatformSettingValueRepository;
import org.coryd.trip.service.OpsAuditWriter;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Platform settings against trip-service's own migrations: validation (range, reason, unknown key), a change applied now
 * (history, audit before -> after, published once committed, read straight away by the trip rules that use it), a scheduled
 * change (not in force before its time, in force at it, applied and recorded by the job), cancelling a schedule, resetting.
 */
@DataJpaTest(properties = "coryd.platform-settings.apply-check-ms=3600000")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({PlatformSettingsService.class, PlatformSettingDefaults.class, OpsAuditWriter.class, PlatformSettings.class,
        CancellationPolicy.class, MatchingProperties.class, StuckTripProperties.class, SosOpsProperties.class, FareProperties.class})
class PlatformSettingsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    static final OpsAdmin ADA = new OpsAdmin("admin-1", "Ada Ops");
    static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    @Autowired PlatformSettingsService service;
    @Autowired PlatformSettings settings;
    @Autowired PlatformSettingValueRepository values;
    @Autowired PlatformSettingChangeRepository changes;
    @Autowired OpsAuditLogRepository audit;
    @Autowired CancellationPolicy cancellationPolicy;
    @Autowired MatchingProperties matchingProperties;
    @Autowired StuckTripProperties stuckTripProperties;
    @Autowired SosOpsProperties sosOpsProperties;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void cleanSlate() {
        changes.deleteAll();
        values.deleteAll();
        audit.deleteAll();
        settings.clear();
        settings.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        service.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        clearInvocations(kafkaTemplate);
    }

    @Test
    void theListShowsEverySettingWithItsDefaultRangeAndUnit() {
        SettingsDtos.SettingsView view = service.list();
        List<SettingsDtos.Setting> all = view.groups().stream().flatMap(g -> g.settings().stream()).toList();
        assertThat(all).hasSize(PlatformSetting.values().length);
        SettingsDtos.Setting fee = all.stream().filter(s -> s.key().equals("cancellation.late-fee-percent")).findFirst().orElseThrow();
        assertThat(fee.defaultValue()).isEqualTo("25");
        assertThat(fee.effectiveValue()).isEqualTo("25");
        assertThat(fee.value()).isNull();
        assertThat(fee.min()).isEqualByComparingTo("0");
        assertThat(fee.max()).isEqualByComparingTo("50");
        assertThat(fee.unit()).isEqualTo("%");
        assertThat(fee.legal()).isTrue();
        assertThat(all).extracting(SettingsDtos.Setting::key).contains("commission.percent", "money.approval-threshold",
                "booking.grace-minutes", "trips.stuck-grace-hours", "safety.sos-realert-minutes", "pricing.fare-tolerance-percent",
                "documents.expiry-reminder-days");
        assertThat(all.stream().filter(s -> s.key().equals("commission.percent")).findFirst().orElseThrow().defaultValue()).isEqualTo("5");
    }

    @Test
    void badChangesAreRefusedWithAReasonOpsCanReadAndNothingIsStored() {
        assertThatThrownBy(() -> service.update("commission.percent", new SettingsDtos.UpdateRequest("40", "too high", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("between 0 and 25");
        assertThatThrownBy(() -> service.update("commission.percent", new SettingsDtos.UpdateRequest("6", " ", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> service.update("no.such.thing", new SettingsDtos.UpdateRequest("6", "x", null), ADA))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update("cancellation.late-fee-percent", new SettingsDtos.UpdateRequest("25", "same", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already 25");
    }

    @Test
    void theFinalDocumentReminderMustComeAfterTheFirst() {
        service.update("documents.final-reminder-days", new SettingsDtos.UpdateRequest("7", "A week's notice", null), ADA);
        assertThatThrownBy(() -> service.update("documents.expiry-reminder-days", new SettingsDtos.UpdateRequest("7", "x", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("final document reminder (7 days)");
        service.update("documents.expiry-reminder-days", new SettingsDtos.UpdateRequest("21", "Three weeks", null), ADA);
        assertThat(service.get("documents.expiry-reminder-days").effectiveValue()).isEqualTo("21");
    }

    @Test
    void aChangeAppliedNowIsRecordedAuditedPublishedAndUsedStraightAway() {
        assertThat(cancellationPolicy.getLateFeePercentage()).isEqualByComparingTo("0.25");
        Instant departure = NOW.plus(Duration.ofHours(3));
        assertThat(cancellationPolicy.applyFee(new BigDecimal("2000"), true)).isEqualByComparingTo("500.00");

        SettingsDtos.UpdateResult result = service.update("cancellation.late-fee-percent",
                new SettingsDtos.UpdateRequest("40", "Board decision 12 Nov", null), ADA);

        assertThat(result.setting().value()).isEqualTo("40");
        assertThat(result.setting().effectiveValue()).isEqualTo("40");
        assertThat(result.setting().lastChange().actorName()).isEqualTo("Ada Ops");
        assertThat(result.warnings()).anyMatch(w -> w.contains("Terms of Service"));
        assertThat(cancellationPolicy.getLateFeePercentage()).as("trip-service's own rule reads it at once").isEqualByComparingTo("0.40");
        assertThat(cancellationPolicy.applyFee(new BigDecimal("2000"), true)).isEqualByComparingTo("800.00");

        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.PLATFORM_SETTINGS), eq("cancellation.late-fee-percent"), sent.capture());
        PlatformSettingChangedEvent event = (PlatformSettingChangedEvent) sent.getValue();
        assertThat(event.getValue()).isEqualTo("40");
        assertThat(event.getVersion()).isEqualTo(1);

        assertThat(changes.findByKeyOrderByCreatedAtDesc("cancellation.late-fee-percent", org.springframework.data.domain.PageRequest.of(0, 5)))
                .extracting(PlatformSettingChange::getAction, PlatformSettingChange::getOldValue, PlatformSettingChange::getNewValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(PlatformSettingChange.Action.SET, null, "40"));
        List<OpsAuditLog> rows = audit.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(OpsAuditLog.TargetType.SETTING, "cancellation.late-fee-percent");
        assertThat(rows).singleElement().satisfies(a -> {
            assertThat(a.getAction()).isEqualTo(OpsAuditLog.Action.SETTING_CHANGED);
            assertThat(a.getActorId()).isEqualTo("admin-1");
            assertThat(a.getBeforeState()).contains("\"25\"");
            assertThat(a.getAfterState()).contains("\"40\"");
            assertThat(a.getReason()).isEqualTo("Board decision 12 Nov");
        });
        assertThat(departure).isAfter(NOW);
    }

    @Test
    void theOtherTripRulesFollowTheirSettings() {
        service.update("booking.grace-minutes", new SettingsDtos.UpdateRequest("25", "Riders keep missing the window", null), ADA);
        service.update("trips.stuck-grace-hours", new SettingsDtos.UpdateRequest("6", "Long-distance pilot", null), ADA);
        service.update("safety.sos-realert-minutes", new SettingsDtos.UpdateRequest("2,10", "Faster escalation", null), ADA);
        service.update("cancellation.free-window-hours", new SettingsDtos.UpdateRequest("4", "Policy v2", null), ADA);

        Instant departure = Instant.parse("2026-11-13T12:00:00Z");
        assertThat(matchingProperties.bookableUntil(departure)).isEqualTo(departure.plus(Duration.ofMinutes(25)));
        assertThat(stuckTripProperties.getGraceHours()).isEqualTo(6);
        assertThat(sosOpsProperties.thresholds()).containsExactly(2, 10);
        assertThat(cancellationPolicy.isFeeApplicable(Instant.now().plus(Duration.ofHours(3)), Instant.now())).isTrue();
    }

    @Test
    void aScheduledChangeWaitsForItsTimeThenTheJobAppliesAndRecordsIt() {
        Instant at = NOW.plus(Duration.ofDays(2));
        SettingsDtos.UpdateResult scheduled = service.update("commission.percent",
                new SettingsDtos.UpdateRequest("7.5", "New pricing from 15 Nov", at), ADA);
        assertThat(scheduled.setting().value()).isNull();
        assertThat(scheduled.setting().effectiveValue()).isEqualTo("5");
        assertThat(scheduled.setting().scheduled().value()).isEqualTo("7.5");
        assertThat(scheduled.setting().scheduled().effectiveFrom()).isEqualTo(at);
        assertThat(scheduled.setting().scheduled().scheduledByName()).isEqualTo("Ada Ops");
        assertThat(settings.decimal(PlatformSetting.COMMISSION_PERCENT, null)).as("not yet").isNull();
        assertThatThrownBy(() -> service.update("commission.percent", new SettingsDtos.UpdateRequest("6", "again", null), ADA))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("cancel it first");

        assertThat(service.applyDue()).as("nothing due before its time").isZero();

        // At the effective time every service's registry already reads the new value - before the job runs.
        settings.useClock(Clock.fixed(at, ZoneOffset.UTC));
        assertThat(settings.decimal(PlatformSetting.COMMISSION_PERCENT, null)).isEqualByComparingTo("7.5");

        service.useClock(Clock.fixed(at.plusSeconds(5), ZoneOffset.UTC));
        clearInvocations(kafkaTemplate);
        assertThat(service.applyDue()).isEqualTo(1);
        SettingsDtos.Setting after = service.get("commission.percent");
        assertThat(after.value()).isEqualTo("7.5");
        assertThat(after.scheduled()).isNull();
        assertThat(after.lastChange().action()).isEqualTo("APPLIED");
        assertThat(after.lastChange().reason()).contains("Ada Ops").contains("New pricing from 15 Nov");
        verify(kafkaTemplate).send(eq(KafkaTopics.PLATFORM_SETTINGS), eq("commission.percent"), any());
        assertThat(audit.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(OpsAuditLog.TargetType.SETTING, "commission.percent"))
                .extracting(OpsAuditLog::getAction)
                .containsExactly(OpsAuditLog.Action.SETTING_SCHEDULED, OpsAuditLog.Action.SETTING_APPLIED);
        assertThat(service.applyDue()).as("applied once").isZero();
    }

    @Test
    void aScheduleCanBeCancelledBeforeItsTimeAndAValueResetToTheDefault() {
        service.update("money.approval-threshold", new SettingsDtos.UpdateRequest("100000", "Raise it", NOW.plus(Duration.ofHours(5))), ADA);
        assertThatThrownBy(() -> service.cancelScheduled("money.approval-threshold", new SettingsDtos.ReasonRequest(""), ADA))
                .isInstanceOf(BadRequestException.class);
        SettingsDtos.UpdateResult cancelled = service.cancelScheduled("money.approval-threshold",
                new SettingsDtos.ReasonRequest("Wrong number"), ADA);
        assertThat(cancelled.setting().scheduled()).isNull();
        assertThat(service.applyDue()).isZero();
        assertThatThrownBy(() -> service.cancelScheduled("money.approval-threshold", new SettingsDtos.ReasonRequest("x"), ADA))
                .hasMessageContaining("no scheduled change");

        assertThatThrownBy(() -> service.reset("money.approval-threshold", new SettingsDtos.ReasonRequest("x"), ADA))
                .hasMessageContaining("already on its default");
        service.update("money.approval-threshold", new SettingsDtos.UpdateRequest("20000", "Tighter", null), ADA);
        assertThat(settings.decimal(PlatformSetting.MONEY_APPROVAL_THRESHOLD, BigDecimal.ONE)).isEqualByComparingTo("20000");
        SettingsDtos.UpdateResult reset = service.reset("money.approval-threshold", new SettingsDtos.ReasonRequest("Back to normal"), ADA);
        assertThat(reset.setting().value()).isNull();
        assertThat(reset.setting().effectiveValue()).isEqualTo("50000");
        assertThat(settings.decimal(PlatformSetting.MONEY_APPROVAL_THRESHOLD, BigDecimal.ONE)).as("fallback again").isEqualByComparingTo("1");
        assertThat(service.history("money.approval-threshold", 0, 10).content()).extracting(SettingsDtos.Change::action)
                .containsExactly("RESET", "SET", "SCHEDULE_CANCELLED", "SCHEDULED");
        assertThat(service.fetchAll()).singleElement().satisfies(e -> {
            assertThat(e.getKey()).isEqualTo("money.approval-threshold");
            assertThat(e.getValue()).isNull();
            assertThat(e.getVersion()).isEqualTo(4);
        });
        verify(kafkaTemplate, atLeastOnce()).send(eq(KafkaTopics.PLATFORM_SETTINGS), eq("money.approval-threshold"), any());
    }

    @Test
    void nothingIsPublishedWhenAChangeIsRefused() {
        assertThatThrownBy(() -> service.update("booking.grace-minutes", new SettingsDtos.UpdateRequest("61", "x", null), ADA))
                .isInstanceOf(BadRequestException.class);
        verify(kafkaTemplate, never()).send(any(), any(), any());
        assertThat(values.count()).isZero();
    }
}
